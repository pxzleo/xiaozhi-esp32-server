package xiaozhi.modules.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MobileAlertDecisionPolicyTest {
    private final MobileAlertDecisionPolicy policy = new MobileAlertDecisionPolicy();

    @Test
    void reclassifiesDeliveryNotificationEvenWhenClientCalledItOther() {
        assertEquals("parcel", policy.category("other",
                "盒马订单开始配送通知：您的订单正在配送中"));
        var decision = policy.deterministic("parcel", "盒马订单开始配送通知：您的订单正在配送中");
        assertEquals("包裹提醒", decision.title());
        assertEquals("有包裹正在配送，请留意接收", decision.spokenSummary());
    }

    @Test
    void recognizesNearArrivalAndPickupButNotOrdinaryOrderPromotion() {
        assertTrue(policy.deterministic("other", "快递即将送达，请保持电话畅通") != null);
        assertTrue(policy.deterministic("other", "您的订单即将配送") != null);
        assertTrue(policy.deterministic("message", "包裹已到丰巢快递柜，请及时取件") != null);
        assertTrue(policy.deterministic("other", "骑手预计10分钟后送达") != null);
        assertEquals(null, policy.deterministic("other", "下单成功，优惠券已到账"));
        assertEquals(null, policy.deterministic("other", "蓝牙设备断开连接"));
    }

    @Test
    void sensitivityChangesModelThresholdWithoutWeakeningDeterministicRules() {
        assertFalse(policy.acceptModel("conservative", "high", 0.89));
        assertTrue(policy.acceptModel("conservative", "critical", 0.90));
        assertTrue(policy.acceptModel("balanced", "high", 0.85));
        assertTrue(policy.acceptModel("timely", "medium", 0.75));
        assertFalse(policy.acceptModel("timely", "low", 0.99));
    }

    @Test
    void deliveryFailureAndNotDeliveredAreNotProgressAlerts() {
        assertNull(policy.deterministic("other", "配送中断，请重新安排配送"));
        assertNull(policy.deterministic("other", "骑手未送达，订单已取消"));
        assertNull(policy.deterministic("other", "预计送达失败，请等待后续通知"));
        assertNull(policy.deterministic("other", "骑手无法按时送达，请联系客服"));
        assertEquals("other", policy.category("other", "派送失败，请联系客服"));
    }

    @Test
    void otpAndNonImminentAppointmentAreNotDeterministicAlerts() {
        assertNull(policy.deterministic("security", "登录验证码 123456，请勿泄露"));
        assertNull(policy.deterministic("appointment", "预约成功，感谢您的使用"));
        assertNull(policy.deterministic("appointment", "上门服务订单已创建"));
        assertNull(policy.deterministic("appointment", "预约编号12: 已创建"));
        assertEquals("appointment", policy.deterministic(
                "other", "上门服务将在今天18点到达").category());
    }

    @Test
    void vehicleAlertUsesFinalOtherCategory() {
        var decision = policy.deterministic("message", "车辆充电完成，请及时驶离");
        assertEquals("other", decision.category());
        assertEquals(xiaozhi.modules.device.proactive.ProactiveEnums.Priority.HIGH,
                decision.priority());
    }
}
