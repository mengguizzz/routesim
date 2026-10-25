/**
 * Deterministic-time motion engine for RouteSim.
 *
 * The engine owns only route/motion state. Android location delivery and UI
 * lifecycle stay in Kotlin. Every tick consumes real elapsed time, so a busy
 * main thread produces fewer samples instead of slow motion.
 */

#include <jni.h>
#include <algorithm>
#include <cmath>
#include <mutex>
#include <random>
#include <vector>

#define JNI_BRIDGE_CLASS "com/opensource/routesim/core/NativeKinematics"

namespace RouteSim {

constexpr double PI = 3.14159265358979323846;
constexpr double EARTH_RADIUS_METERS = 6378137.0;
constexpr double DEG_TO_RAD = PI / 180.0;
constexpr double RAD_TO_DEG = 180.0 / PI;

enum class SimMode : int {
    RUNNING = 0,
    CYCLING = 1,
    DRIVING = 2,
};

struct GeoPoint {
    double lat;
    double lng;
};

struct SimSnapshot {
    double lat = 0.0;
    double lng = 0.0;
    double speedMps = 0.0;
    double bearingDeg = 0.0;
    double accuracyMeters = 4.0;
    int totalSteps = 0;
    int cadenceSpm = 0;
    double distanceMeters = 0.0;
    double elapsedSeconds = 0.0;
    int lapCount = 0;
    bool finished = true;
};

class KinematicsEngine {
public:
    KinematicsEngine() {
        std::random_device device;
        rng_.seed(device());
    }

    void initRoute(
            const std::vector<GeoPoint>& source,
            SimMode mode,
            double minSpeedKmh,
            double maxSpeedKmh,
            double minHoldSeconds,
            double maxHoldSeconds,
            bool loopRoute) {
        std::lock_guard<std::mutex> lock(mutex_);

        route_.clear();
        for (const auto& point : source) {
            if (route_.empty() || haversineDistance(route_.back(), point) > 0.02) {
                route_.push_back(point);
            }
        }

        mode_ = mode;
        minSpeedKmh_ = std::max(1.0, minSpeedKmh);
        maxSpeedKmh_ = std::max(minSpeedKmh_, maxSpeedKmh);
        minHoldSeconds_ = std::max(1.0, minHoldSeconds);
        maxHoldSeconds_ = std::max(minHoldSeconds_, maxHoldSeconds);
        loopRoute_ = loopRoute;

        if (loopRoute_ && route_.size() >= 2 &&
            haversineDistance(route_.front(), route_.back()) > 0.5) {
            route_.push_back(route_.front());
        }

        currentSegmentIdx_ = 0;
        currentPos_ = route_.empty() ? GeoPoint{0.0, 0.0} : route_.front();
        currentBearing_ = route_.size() >= 2 ? bearing(route_[0], route_[1]) : 0.0;
        currentSpeedKmh_ = 0.0;
        targetSpeedKmh_ = randomInRange(minSpeedKmh_, maxSpeedKmh_);
        currentCadenceSpm_ = 0.0;
        speedHoldTimerSec_ = randomInRange(minHoldSeconds_, maxHoldSeconds_);
        stepAccumulator_ = 0.0;
        elapsedSeconds_ = 0.0;
        travelledMeters_ = 0.0;
        totalSteps_ = 0;
        lapCount_ = 0;
        finished_ = route_.size() < 2;
    }

    SimSnapshot tick(double dtSeconds) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (finished_ || route_.size() < 2 || dtSeconds <= 0.0) {
            return snapshot(0.0, 0.0);
        }

        const double dt = std::clamp(dtSeconds, 0.01, 5.0);
        elapsedSeconds_ += dt;
        speedHoldTimerSec_ -= dt;

        updateTargetSpeed();
        applyLongitudinalDynamics(dt);
        updateCadence(dt);

        const double moveMeters = (currentSpeedKmh_ / 3.6) * dt;
        advanceAlongRoute(moveMeters);

        const double jitterSigma = jitterSigmaMeters();
        const double northOffset = normal_(rng_) * jitterSigma;
        const double eastOffset = normal_(rng_) * jitterSigma;
        const double jitterLat = northOffset / 111320.0;
        const double cosLat = std::cos(currentPos_.lat * DEG_TO_RAD);
        const double jitterLng = std::abs(cosLat) > 1e-8
                ? eastOffset / (111320.0 * cosLat)
                : 0.0;
        return snapshot(jitterLat, jitterLng);
    }

private:
    std::mutex mutex_;
    std::mt19937 rng_;
    std::normal_distribution<double> normal_{0.0, 1.0};

    std::vector<GeoPoint> route_;
    size_t currentSegmentIdx_ = 0;
    GeoPoint currentPos_{0.0, 0.0};

    SimMode mode_ = SimMode::RUNNING;
    bool loopRoute_ = true;
    bool finished_ = true;

    double minSpeedKmh_ = 6.0;
    double maxSpeedKmh_ = 14.0;
    double minHoldSeconds_ = 8.0;
    double maxHoldSeconds_ = 35.0;
    double currentSpeedKmh_ = 0.0;
    double targetSpeedKmh_ = 8.0;
    double speedHoldTimerSec_ = 0.0;
    double currentCadenceSpm_ = 0.0;
    double currentBearing_ = 0.0;
    double stepAccumulator_ = 0.0;
    int totalSteps_ = 0;
    int lapCount_ = 0;
    double elapsedSeconds_ = 0.0;
    double travelledMeters_ = 0.0;

    void updateTargetSpeed() {
        if (speedHoldTimerSec_ <= 0.0) {
            targetSpeedKmh_ = randomInRange(minSpeedKmh_, maxSpeedKmh_);
            speedHoldTimerSec_ = randomInRange(minHoldSeconds_, maxHoldSeconds_);
        }

        if (mode_ == SimMode::DRIVING || mode_ == SimMode::CYCLING) {
            targetSpeedKmh_ = std::min(targetSpeedKmh_, curveSpeedLimitKmh());
        }

        if (!loopRoute_) {
            const double remaining = remainingRouteDistanceMeters();
            const double deceleration = maxDecelerationMps2();
            const double speedMps = currentSpeedKmh_ / 3.6;
            const double brakingDistance = speedMps * speedMps / (2.0 * deceleration);
            if (remaining <= brakingDistance + 0.5) {
                const double safeSpeedMps = std::sqrt(
                        std::max(0.0, 2.0 * deceleration * std::max(0.0, remaining - 0.02)));
                targetSpeedKmh_ = std::min(targetSpeedKmh_, safeSpeedMps * 3.6);
            }
        }
    }

    void applyLongitudinalDynamics(double dt) {
        const double difference = targetSpeedKmh_ - currentSpeedKmh_;
        const double limitMps2 = difference >= 0.0
                ? maxAccelerationMps2()
                : maxDecelerationMps2();
        const double maxDeltaKmh = limitMps2 * 3.6 * dt;
        currentSpeedKmh_ = std::clamp(
                currentSpeedKmh_ + std::clamp(difference, -maxDeltaKmh, maxDeltaKmh),
                0.0,
                std::max(minSpeedKmh_, maxSpeedKmh_));
    }

    void updateCadence(double dt) {
        double targetCadence = 0.0;
        if (mode_ == SimMode::RUNNING && currentSpeedKmh_ > 0.25) {
            targetCadence = 145.0 + currentSpeedKmh_ * 3.2 + normal_(rng_) * 1.2;
            targetCadence = std::clamp(targetCadence, 132.0, 198.0);
        } else if (mode_ == SimMode::CYCLING && currentSpeedKmh_ > 0.25) {
            targetCadence = 62.0 + currentSpeedKmh_ * 1.35 + normal_(rng_) * 0.8;
            targetCadence = std::clamp(targetCadence, 62.0, 108.0);
        }

        // Human cadence settles within a few steps when the runner starts,
        // rather than climbing by only one leg turn per second.
        const double cadenceStep = (mode_ == SimMode::RUNNING ? 48.0 : 14.0) * dt;
        currentCadenceSpm_ += std::clamp(
                targetCadence - currentCadenceSpm_,
                -cadenceStep,
                cadenceStep);
        if (currentCadenceSpm_ < 0.5) {
            currentCadenceSpm_ = 0.0;
        }

        if (mode_ == SimMode::RUNNING && currentCadenceSpm_ > 0.0) {
            stepAccumulator_ += (currentCadenceSpm_ / 60.0) * dt;
            const int completeSteps = static_cast<int>(std::floor(stepAccumulator_));
            if (completeSteps > 0) {
                totalSteps_ += completeSteps;
                stepAccumulator_ -= static_cast<double>(completeSteps);
            }
        }
    }

    void advanceAlongRoute(double remainingMeters) {
        if (remainingMeters <= 0.0) {
            return;
        }

        while (remainingMeters > 1e-6 && currentSegmentIdx_ + 1 < route_.size()) {
            const GeoPoint& target = route_[currentSegmentIdx_ + 1];
            const double toTarget = haversineDistance(currentPos_, target);

            if (toTarget <= 1e-5) {
                currentPos_ = target;
                currentSegmentIdx_++;
                continue;
            }

            currentBearing_ = bearing(currentPos_, target);
            if (remainingMeters >= toTarget) {
                currentPos_ = target;
                remainingMeters -= toTarget;
                travelledMeters_ += toTarget;
                currentSegmentIdx_++;
                if (currentSegmentIdx_ >= route_.size() - 1) {
                    if (loopRoute_) {
                        lapCount_++;
                        currentPos_ = route_.front();
                        currentSegmentIdx_ = 0;
                        currentBearing_ = bearing(route_[0], route_[1]);
                    } else {
                        finished_ = true;
                        currentSpeedKmh_ = 0.0;
                        currentCadenceSpm_ = 0.0;
                        break;
                    }
                }
            } else {
                const double ratio = remainingMeters / toTarget;
                currentPos_.lat += (target.lat - currentPos_.lat) * ratio;
                currentPos_.lng += (target.lng - currentPos_.lng) * ratio;
                travelledMeters_ += remainingMeters;
                remainingMeters = 0.0;
            }
        }
    }

    double remainingRouteDistanceMeters() const {
        if (route_.size() < 2 || currentSegmentIdx_ + 1 >= route_.size()) {
            return 0.0;
        }
        double remaining = haversineDistance(currentPos_, route_[currentSegmentIdx_ + 1]);
        for (size_t index = currentSegmentIdx_ + 1; index + 1 < route_.size(); ++index) {
            remaining += haversineDistance(route_[index], route_[index + 1]);
        }
        return remaining;
    }

    double curveSpeedLimitKmh() const {
        if (route_.size() < 3 || currentSegmentIdx_ + 1 >= route_.size()) {
            return maxSpeedKmh_;
        }

        const double lookAhead = mode_ == SimMode::DRIVING ? 75.0 : 30.0;
        double accumulated = 0.0;
        GeoPoint lookAheadPoint = route_[currentSegmentIdx_ + 1];
        for (size_t index = currentSegmentIdx_ + 1; index < route_.size(); ++index) {
            accumulated += haversineDistance(
                    index == currentSegmentIdx_ + 1 ? currentPos_ : route_[index - 1],
                    route_[index]);
            lookAheadPoint = route_[index];
            if (accumulated >= lookAhead) {
                break;
            }
        }

        const double angle = std::abs(shortestAngle(
                currentBearing_,
                bearing(currentPos_, lookAheadPoint)));
        if (angle < 12.0) {
            return maxSpeedKmh_;
        }

        const double loss = std::min(0.68, (angle / 120.0) * 0.68);
        return std::max(minSpeedKmh_ * 0.55, maxSpeedKmh_ * (1.0 - loss));
    }

    double jitterSigmaMeters() const {
        if (currentSpeedKmh_ < 0.2) {
            return 0.06;
        }
        switch (mode_) {
            case SimMode::RUNNING:
                return 0.14;
            case SimMode::CYCLING:
                return 0.22;
            case SimMode::DRIVING:
                return 0.30;
        }
        return 0.20;
    }

    double maxAccelerationMps2() const {
        switch (mode_) {
            case SimMode::RUNNING:
                return 0.72;
            case SimMode::CYCLING:
                return 0.55;
            case SimMode::DRIVING:
                return 1.55;
        }
        return 0.7;
    }

    double maxDecelerationMps2() const {
        switch (mode_) {
            case SimMode::RUNNING:
                return 1.05;
            case SimMode::CYCLING:
                return 1.30;
            case SimMode::DRIVING:
                return 2.80;
        }
        return 1.0;
    }

    SimSnapshot snapshot(double jitterLat, double jitterLng) {
        SimSnapshot result;
        result.lat = currentPos_.lat + jitterLat;
        result.lng = currentPos_.lng + jitterLng;
        result.speedMps = finished_ ? 0.0 : currentSpeedKmh_ / 3.6;
        result.bearingDeg = currentBearing_;
        result.accuracyMeters = 3.2 + std::abs(normal_(rng_)) * 1.8;
        result.totalSteps = mode_ == SimMode::RUNNING ? totalSteps_ : 0;
        result.cadenceSpm = finished_ ? 0 : static_cast<int>(std::lround(currentCadenceSpm_));
        result.distanceMeters = travelledMeters_;
        result.elapsedSeconds = elapsedSeconds_;
        result.lapCount = lapCount_;
        result.finished = finished_;
        return result;
    }

    double randomInRange(double minimum, double maximum) {
        if (maximum <= minimum) {
            return minimum;
        }
        std::uniform_real_distribution<double> distribution(minimum, maximum);
        return distribution(rng_);
    }

    static double shortestAngle(double first, double second) {
        return std::fmod(second - first + 540.0, 360.0) - 180.0;
    }

    static double haversineDistance(const GeoPoint& first, const GeoPoint& second) {
        const double dLat = (second.lat - first.lat) * DEG_TO_RAD;
        const double dLng = (second.lng - first.lng) * DEG_TO_RAD;
        const double lat1 = first.lat * DEG_TO_RAD;
        const double lat2 = second.lat * DEG_TO_RAD;
        const double sinLat = std::sin(dLat * 0.5);
        const double sinLng = std::sin(dLng * 0.5);
        const double h = sinLat * sinLat +
                std::cos(lat1) * std::cos(lat2) * sinLng * sinLng;
        return 2.0 * EARTH_RADIUS_METERS *
                std::atan2(std::sqrt(h), std::sqrt(std::max(0.0, 1.0 - h)));
    }

    static double bearing(const GeoPoint& first, const GeoPoint& second) {
        const double lat1 = first.lat * DEG_TO_RAD;
        const double lat2 = second.lat * DEG_TO_RAD;
        const double dLng = (second.lng - first.lng) * DEG_TO_RAD;
        const double y = std::sin(dLng) * std::cos(lat2);
        const double x = std::cos(lat1) * std::sin(lat2) -
                std::sin(lat1) * std::cos(lat2) * std::cos(dLng);
        return std::fmod(std::atan2(y, x) * RAD_TO_DEG + 360.0, 360.0);
    }
};

KinematicsEngine engine;

} // namespace RouteSim

static void nativeInitRoute(
        JNIEnv* env,
        jobject,
        jdoubleArray latLngFlatArray,
        jint mode,
        jdouble minSpeedKmh,
        jdouble maxSpeedKmh,
        jdouble minHoldSeconds,
        jdouble maxHoldSeconds,
        jboolean loopRoute) {
    const jsize length = env->GetArrayLength(latLngFlatArray);
    jdouble* values = env->GetDoubleArrayElements(latLngFlatArray, nullptr);

    std::vector<RouteSim::GeoPoint> points;
    points.reserve(static_cast<size_t>(length / 2));
    for (jsize index = 0; index + 1 < length; index += 2) {
        points.push_back({values[index], values[index + 1]});
    }
    env->ReleaseDoubleArrayElements(latLngFlatArray, values, JNI_ABORT);

    RouteSim::engine.initRoute(
            points,
            static_cast<RouteSim::SimMode>(mode),
            minSpeedKmh,
            maxSpeedKmh,
            minHoldSeconds,
            maxHoldSeconds,
            loopRoute == JNI_TRUE);
}

static jdoubleArray nativeTick(JNIEnv* env, jobject, jdouble dtSeconds) {
    const RouteSim::SimSnapshot result = RouteSim::engine.tick(dtSeconds);
    const jdouble output[11] = {
            result.lat,
            result.lng,
            result.speedMps,
            result.bearingDeg,
            result.accuracyMeters,
            static_cast<double>(result.totalSteps),
            static_cast<double>(result.cadenceSpm),
            result.distanceMeters,
            result.elapsedSeconds,
            static_cast<double>(result.lapCount),
            result.finished ? 1.0 : 0.0,
    };

    jdoubleArray snapshot = env->NewDoubleArray(11);
    env->SetDoubleArrayRegion(snapshot, 0, 11, output);
    return snapshot;
}

static const JNINativeMethod methods[] = {
        {"nativeInitRoute", "([DIDDDDZ)V", reinterpret_cast<void*>(nativeInitRoute)},
        {"nativeTick", "(D)[D", reinterpret_cast<void*>(nativeTick)},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass bridgeClass = env->FindClass(JNI_BRIDGE_CLASS);
    if (bridgeClass == nullptr) {
        return JNI_ERR;
    }
    if (env->RegisterNatives(
            bridgeClass,
            methods,
            sizeof(methods) / sizeof(methods[0])) < 0) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
