package xiaozhi.modules.device.proactive;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import xiaozhi.common.exception.RenException;

public final class ProactiveEnums {
    private ProactiveEnums() {}

    public interface WireEnum {
        String name();

        @JsonValue
        default String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static <E extends Enum<E> & WireEnum> E parseWire(Class<E> type, String value) {
        if (value == null || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new RenException(type.getSimpleName() + "枚举值无效");
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new RenException(type.getSimpleName() + "枚举值无效", exception);
        }
    }

    public enum Mode implements WireEnum {
        CONSERVATIVE, ACTIVE, AGGRESSIVE, TODAY_SILENT;
        @JsonCreator public static Mode fromWire(String value) { return parseWire(Mode.class, value); }
    }
    public enum Topic implements WireEnum {
        REMINDER, CALENDAR, WEATHER, NEWS, MUSIC, HEALTH, HABIT, SYSTEM;
        @JsonCreator public static Topic fromWire(String value) { return parseWire(Topic.class, value); }
    }
    public enum Priority implements WireEnum {
        CRITICAL, HIGH, NORMAL, LOW;
        @JsonCreator public static Priority fromWire(String value) { return parseWire(Priority.class, value); }
    }
    public enum EventType implements WireEnum {
        REMINDER, DUE_SOON, SCHEDULE_CHANGE, WEATHER_ALERT, NEWS_ALERT, MUSIC_STATUS, HABIT_SUGGESTION, SYSTEM;
        @JsonCreator public static EventType fromWire(String value) { return parseWire(EventType.class, value); }
    }
    public enum DeliveryStatus implements WireEnum {
        PENDING, CLAIMED, DELIVERED, FAILED, EXPIRED, DISMISSED;
        @JsonCreator public static DeliveryStatus fromWire(String value) { return parseWire(DeliveryStatus.class, value); }
    }
    public enum Outcome implements WireEnum {
        NONE, ACKNOWLEDGED, COMPLETED, DISMISSED, FAILED;
        @JsonCreator public static Outcome fromWire(String value) { return parseWire(Outcome.class, value); }
    }
    public enum HabitType implements WireEnum {
        TIME_PATTERN, REPEATED_ACTION, CONTENT_PREFERENCE;
        @JsonCreator public static HabitType fromWire(String value) { return parseWire(HabitType.class, value); }
    }
    public enum MonitorType implements WireEnum {
        WEATHER, NEWS;
        @JsonCreator public static MonitorType fromWire(String value) { return parseWire(MonitorType.class, value); }
    }
    public enum WeatherHazardType implements WireEnum {
        RAINSTORM, THUNDERSTORM, HAIL, BLIZZARD, HIGH_WIND, HIGH_TEMPERATURE,
        LOW_TEMPERATURE, TEMPERATURE_DROP;
        @JsonCreator public static WeatherHazardType fromWire(String value) {
            return parseWire(WeatherHazardType.class, value);
        }
    }
    public enum NewsCategory implements WireEnum {
        PUBLIC_SAFETY, NATURAL_DISASTER, MAJOR_POLICY, INTERNATIONAL_CONFLICT,
        MAJOR_ECONOMY, MAJOR_TECHNOLOGY;
        @JsonCreator public static NewsCategory fromWire(String value) {
            return parseWire(NewsCategory.class, value);
        }
    }
}
