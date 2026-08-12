package xiaozhi.modules.mobile;

import java.util.Set;
import java.util.Arrays;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;

@Component
public class MobileAlertDecisionPolicy {
    private static final Pattern SECURITY = Pattern.compile(
            "异常登录|异地登录|支付异常|盗刷|账户冻结|诈骗|非本人操作");
    private static final Pattern MISSED_CALL = Pattern.compile("未接来电|漏接来电|电话未接");
    private static final Pattern PARCEL = Pattern.compile(
            "开始配送|准备配送|即将配送|正在配送|配送中|即将送达|预计.{0,8}送达|骑手.{0,12}送达|派送中|正在派送|"
            + "已到.{0,12}(驿站|快递柜|丰巢|代收点)|待取件|请取件|可以取件|取件码");
    private static final Pattern PARCEL_NEGATIVE = Pattern.compile(
            "配送.{0,8}(中断|失败|取消)|派送.{0,8}(中断|失败|取消)|"
            + "送达.{0,8}(中断|失败|取消)|未.{0,4}送达|无法.{0,8}送达|取消.{0,4}(配送|派送)");
    private static final Pattern APPOINTMENT = Pattern.compile(
            "预约.{0,12}(提醒|即将|今天|明天|将于|将在)|会议.{0,12}(开始|即将)|"
            + "就诊.{0,12}(提醒|即将|今天|明天|将于|将在)|"
            + "上门.{0,12}(提醒|即将|今天|明天|将于|将在)|航班.{0,12}(起飞|延误|取消)|"
            + "火车.{0,12}(出发|晚点|取消)");
    private static final Pattern VEHICLE = Pattern.compile(
            "充电.{0,8}(完成|结束)|请及时驶离|车辆.{0,8}(异常|告警|断开连接)|车门未关|车窗未关");
    private static final Set<String> CATEGORIES = Set.of(
            "security", "call", "parcel", "appointment", "message", "other");

    public record DeterministicDecision(String category, String title, String spokenSummary,
            Priority priority) {}

    public String category(String supplied, String summary) {
        String text = StringUtils.defaultString(summary);
        if (isParcelProgress(text)) return "parcel";
        if (MISSED_CALL.matcher(text).find()) return "call";
        if (SECURITY.matcher(text).find()) return "security";
        if (APPOINTMENT.matcher(text).find()) return "appointment";
        return CATEGORIES.contains(supplied) ? supplied : "other";
    }

    public DeterministicDecision deterministic(String suppliedCategory, String summary) {
        if (StringUtils.isBlank(summary)) return null;
        String category = category(suppliedCategory, summary);
        if (isParcelProgress(summary)) {
            String spoken = summary.matches(".*(已到|待取件|请取件|可以取件|取件码).*")
                    ? "有包裹已经到达，请及时取件" : "有包裹正在配送，请留意接收";
            return new DeterministicDecision("parcel", "包裹提醒", spoken,
                    Priority.HIGH);
        }
        if (MISSED_CALL.matcher(summary).find()) {
            return new DeterministicDecision("call", "来电提醒", "您有未接来电，请留意查看",
                    Priority.HIGH);
        }
        if (SECURITY.matcher(summary).find()) {
            return new DeterministicDecision("security", "安全提醒", "检测到账户安全提醒，请及时查看手机",
                    Priority.CRITICAL);
        }
        if (APPOINTMENT.matcher(summary).find()) {
            return new DeterministicDecision("appointment", "行程提醒", "您有即将发生的行程或预约，请及时查看",
                    Priority.HIGH);
        }
        if (VEHICLE.matcher(summary).find()) {
            return new DeterministicDecision("other", "车辆提醒", "车辆状态发生重要变化，请及时查看",
                    Priority.HIGH);
        }
        return null;
    }

    public boolean acceptModel(String sensitivity, String severity, double confidence) {
        return switch (StringUtils.defaultIfBlank(sensitivity, "balanced")) {
            case "conservative" -> "critical".equals(severity) && confidence >= 0.90;
            case "timely" -> Set.of("medium", "high", "critical").contains(severity)
                    && confidence >= 0.75;
            default -> Set.of("high", "critical").contains(severity) && confidence >= 0.85;
        };
    }

    public boolean enabledCategory(String configured, String category) {
        String value = StringUtils.defaultIfBlank(configured,
                "security,call,parcel,appointment,message,other");
        return Arrays.stream(value.split(",")).anyMatch(category::equals);
    }

    private boolean isParcelProgress(String summary) {
        return !PARCEL_NEGATIVE.matcher(summary).find() && PARCEL.matcher(summary).find();
    }
}
