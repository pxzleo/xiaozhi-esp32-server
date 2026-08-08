<template>
  <el-dialog
    :title="$t('proactive.title')"
    :visible="visible"
    width="1120px"
    top="5vh"
    class="proactive-dialog"
    :close-on-click-modal="false"
    @open="handleOpen"
    @close="$emit('update:visible', false)"
  >
    <div class="device-context">
      <div>
        <strong>{{ device.remark || device.model || '-' }}</strong>
        <span>{{ device.model || '-' }}</span>
      </div>
      <MacAddressMask :macAddress="device.macAddress" />
    </div>

    <el-tabs v-model="activeTab" @tab-click="handleTabChange">
      <el-tab-pane :label="$t('proactive.settings')" name="settings">
        <div v-loading="preferenceLoading" class="section-body settings-body">
          <div v-if="isSilentToday" class="silent-notice">
            <span>{{ $t('proactive.silentUntil', { time: formatTime(preference.silent_until) }) }}</span>
          </div>
          <el-form ref="preferenceForm" :model="form" label-width="150px" size="small">
            <el-form-item :label="$t('proactive.mode')">
              <el-radio-group v-model="form.mode" @change="handleModeChange">
                <el-radio-button v-for="mode in modes" :key="mode" :label="mode">
                  {{ enumLabel('mode', mode) }}
                </el-radio-button>
              </el-radio-group>
              <div class="field-help">{{ $t(`proactive.modeHelp.${form.mode}`) }}</div>
            </el-form-item>
            <el-form-item :label="$t('proactive.dailyLimit')">
              <el-input-number v-model="form.daily_limit" :min="1" :max="modeMaximum" />
              <span class="inline-help">{{ $t('proactive.dailyLimitHelp', { max: modeMaximum }) }}</span>
            </el-form-item>
            <el-form-item :label="$t('proactive.quietHours')">
              <el-time-picker
                v-model="form.quiet_start"
                value-format="HH:mm"
                format="HH:mm"
                :placeholder="$t('proactive.quietStart')"
              />
              <span class="time-separator">—</span>
              <el-time-picker
                v-model="form.quiet_end"
                value-format="HH:mm"
                format="HH:mm"
                :placeholder="$t('proactive.quietEnd')"
              />
              <el-button v-if="form.quiet_start || form.quiet_end" type="text" @click="clearQuietHours">
                {{ $t('proactive.clear') }}
              </el-button>
            </el-form-item>
            <el-form-item :label="$t('proactive.allowedTopics')">
              <el-checkbox-group v-model="form.allowed_topics">
                <el-checkbox v-for="topic in topics" :key="topic" :label="topic" :disabled="form.blocked_topics.includes(topic)">
                  {{ enumLabel('topic', topic) }}
                </el-checkbox>
              </el-checkbox-group>
              <div class="field-help">{{ $t('proactive.allowedTopicsHelp') }}</div>
            </el-form-item>
            <el-form-item :label="$t('proactive.blockedTopics')">
              <el-checkbox-group v-model="form.blocked_topics">
                <el-checkbox v-for="topic in topics" :key="topic" :label="topic" :disabled="form.allowed_topics.includes(topic)">
                  {{ enumLabel('topic', topic) }}
                </el-checkbox>
              </el-checkbox-group>
            </el-form-item>
          </el-form>
          <div class="settings-actions">
            <el-button type="primary" size="small" :loading="saving" @click="savePreference">
              {{ $t('proactive.save') }}
            </el-button>
            <el-button size="small" :disabled="isSilentToday" :loading="silencing" @click="silentToday">
              {{ $t('proactive.silentToday') }}
            </el-button>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane :label="$t('proactive.events')" name="events">
        <div class="section-body">
          <div class="filter-row">
            <el-select v-model="eventFilters.topic" clearable size="small" :placeholder="$t('proactive.topic')">
              <el-option v-for="topic in topics" :key="topic" :label="enumLabel('topic', topic)" :value="topic" />
            </el-select>
            <el-select v-model="eventFilters.event_type" clearable size="small" :placeholder="$t('proactive.eventType')">
              <el-option v-for="type in eventTypes" :key="type" :label="enumLabel('eventType', type)" :value="type" />
            </el-select>
            <el-select v-model="eventFilters.delivery_status" clearable size="small" :placeholder="$t('proactive.deliveryStatus')">
              <el-option v-for="status in deliveryStatuses" :key="status" :label="enumLabel('deliveryStatus', status)" :value="status" />
            </el-select>
            <el-button type="primary" size="small" icon="el-icon-search" @click="applyEventFilters">
              {{ $t('proactive.filter') }}
            </el-button>
          </div>
          <el-table v-loading="eventsLoading" :data="events" size="small" :empty-text="$t('proactive.noEvents')">
            <el-table-column :label="$t('proactive.createdAt')" width="160">
              <template slot-scope="scope">{{ formatTime(scope.row.created_at) }}</template>
            </el-table-column>
            <el-table-column :label="$t('proactive.topic')" width="105">
              <template slot-scope="scope">{{ enumLabel('topic', scope.row.topic) }}</template>
            </el-table-column>
            <el-table-column :label="$t('proactive.eventType')" width="145">
              <template slot-scope="scope">{{ enumLabel('eventType', scope.row.event_type) }}</template>
            </el-table-column>
            <el-table-column prop="reason" :label="$t('proactive.reason')" min-width="220" show-overflow-tooltip />
            <el-table-column :label="$t('proactive.deliveryStatus')" width="115">
              <template slot-scope="scope">{{ enumLabel('deliveryStatus', scope.row.delivery_status) }}</template>
            </el-table-column>
            <el-table-column :label="$t('proactive.outcome')" width="120">
              <template slot-scope="scope">{{ enumLabel('outcome', scope.row.outcome) }}</template>
            </el-table-column>
          </el-table>
          <div class="pagination-row">
            <el-pagination
              :current-page="eventFilters.page"
              :page-size="eventFilters.limit"
              :page-sizes="[10, 20, 50]"
              :total="eventTotal"
              layout="sizes, prev, pager, next, total"
              small
              @size-change="handleEventSizeChange"
              @current-change="handleEventPageChange"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane :label="$t('proactive.habits')" name="habits">
        <div class="section-body">
          <el-table v-loading="habitsLoading" :data="habits" size="small" :empty-text="$t('proactive.noHabits')">
            <el-table-column :label="$t('proactive.habitType')" width="160">
              <template slot-scope="scope">{{ enumLabel('habitType', scope.row.habit_type) }}</template>
            </el-table-column>
            <el-table-column prop="habit_key" :label="$t('proactive.habitKey')" min-width="220" show-overflow-tooltip />
            <el-table-column prop="evidence_count" :label="$t('proactive.evidenceCount')" width="110" />
            <el-table-column :label="$t('proactive.habitStatus')" width="130">
              <template slot-scope="scope">{{ habitStatus(scope.row) }}</template>
            </el-table-column>
            <el-table-column :label="$t('proactive.lastSeenAt')" width="170">
              <template slot-scope="scope">{{ formatTime(scope.row.last_seen_at) }}</template>
            </el-table-column>
            <el-table-column :label="$t('proactive.operation')" width="90" align="right">
              <template slot-scope="scope">
                <el-button type="text" size="small" class="delete-button" @click="deleteHabit(scope.row)">
                  {{ $t('proactive.delete') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<script>
import Api from '@/apis/api';
import MacAddressMask from '@/components/MacAddressMask.vue';
import {
  PROACTIVE_DELIVERY_STATUSES,
  PROACTIVE_EVENT_TYPES,
  PROACTIVE_MODES,
  PROACTIVE_TOPICS,
  createPreferenceForm,
  defaultDailyLimit,
  preferencePayload,
  validatePreference,
} from '@/utils/proactiveAssistant.mjs';

export default {
  name: 'ProactiveAssistantDialog',
  components: { MacAddressMask },
  props: {
    visible: { type: Boolean, default: false },
    device: { type: Object, default: () => ({}) },
  },
  data() {
    return {
      activeTab: 'settings',
      preference: {},
      form: createPreferenceForm(),
      preferenceLoading: false,
      saving: false,
      silencing: false,
      modes: PROACTIVE_MODES,
      topics: PROACTIVE_TOPICS,
      eventTypes: PROACTIVE_EVENT_TYPES,
      deliveryStatuses: PROACTIVE_DELIVERY_STATUSES,
      events: [],
      eventTotal: 0,
      eventsLoading: false,
      eventFilters: { topic: '', event_type: '', delivery_status: '', page: 1, limit: 20 },
      habits: [],
      habitsLoading: false,
    };
  },
  computed: {
    isSilentToday() {
      return this.preference.mode === 'today_silent';
    },
    modeMaximum() {
      return { conservative: 1, active: 3, aggressive: 5 }[this.form.mode] || 1;
    },
  },
  methods: {
    handleOpen() {
      this.activeTab = 'settings';
      this.eventFilters = { topic: '', event_type: '', delivery_status: '', page: 1, limit: 20 };
      this.events = [];
      this.habits = [];
      this.loadPreference();
    },
    handleTabChange() {
      if (this.activeTab === 'events') this.loadEvents();
      if (this.activeTab === 'habits') this.loadHabits();
    },
    responseData(response) {
      return response && response.data ? response.data.data : null;
    },
    errorMessage(error, fallbackKey) {
      return error && error.data && error.data.msg ? error.data.msg : this.$t(fallbackKey);
    },
    loadPreference() {
      this.preferenceLoading = true;
      Api.proactive.getPreference(this.device.device_id, response => {
        this.preferenceLoading = false;
        this.preference = this.responseData(response) || {};
        this.form = createPreferenceForm(this.preference);
      }, error => {
        this.preferenceLoading = false;
        this.$message.error(this.errorMessage(error, 'proactive.loadPreferenceFailed'));
      });
    },
    handleModeChange(mode) {
      this.form.daily_limit = defaultDailyLimit(mode);
    },
    clearQuietHours() {
      this.form.quiet_start = '';
      this.form.quiet_end = '';
    },
    savePreference() {
      const invalidField = validatePreference(this.form);
      if (invalidField) {
        this.$message.warning(this.$t(`proactive.validation.${invalidField}`));
        return;
      }
      this.saving = true;
      Api.proactive.updatePreference(this.device.device_id, preferencePayload(this.form), response => {
        this.saving = false;
        this.preference = this.responseData(response) || {};
        this.form = createPreferenceForm(this.preference);
        this.$message.success(this.$t('proactive.saveSuccess'));
      }, error => {
        this.saving = false;
        this.$message.error(this.errorMessage(error, 'proactive.saveFailed'));
      });
    },
    silentToday() {
      this.$confirm(this.$t('proactive.silentTodayConfirm'), this.$t('message.warning'), {
        confirmButtonText: this.$t('button.ok'),
        cancelButtonText: this.$t('button.cancel'),
        type: 'warning',
      }).then(() => {
        this.silencing = true;
        Api.proactive.silentToday(this.device.device_id, response => {
          this.silencing = false;
          this.preference = this.responseData(response) || {};
          this.form = createPreferenceForm(this.preference);
          this.$message.success(this.$t('proactive.silentTodaySuccess'));
        }, error => {
          this.silencing = false;
          this.$message.error(this.errorMessage(error, 'proactive.silentTodayFailed'));
        });
      }).catch(() => {});
    },
    applyEventFilters() {
      this.eventFilters.page = 1;
      this.loadEvents();
    },
    loadEvents() {
      this.eventsLoading = true;
      Api.proactive.getEvents({ ...this.eventFilters, device_id: this.device.device_id }, response => {
        this.eventsLoading = false;
        const data = this.responseData(response) || {};
        this.events = Array.isArray(data.list) ? data.list : [];
        this.eventTotal = Number(data.total) || 0;
      }, error => {
        this.eventsLoading = false;
        this.$message.error(this.errorMessage(error, 'proactive.loadEventsFailed'));
      });
    },
    handleEventSizeChange(limit) {
      this.eventFilters.limit = limit;
      this.eventFilters.page = 1;
      this.loadEvents();
    },
    handleEventPageChange(page) {
      this.eventFilters.page = page;
      this.loadEvents();
    },
    loadHabits() {
      this.habitsLoading = true;
      Api.proactive.getHabits(this.device.device_id, response => {
        this.habitsLoading = false;
        const data = this.responseData(response);
        this.habits = Array.isArray(data) ? data : [];
      }, error => {
        this.habitsLoading = false;
        this.$message.error(this.errorMessage(error, 'proactive.loadHabitsFailed'));
      });
    },
    deleteHabit(habit) {
      this.$confirm(this.$t('proactive.deleteHabitConfirm'), this.$t('message.warning'), {
        confirmButtonText: this.$t('button.ok'),
        cancelButtonText: this.$t('button.cancel'),
        type: 'warning',
      }).then(() => {
        Api.proactive.deleteHabit(habit.id, () => {
          this.$message.success(this.$t('proactive.deleteHabitSuccess'));
          this.loadHabits();
        }, error => this.$message.error(this.errorMessage(error, 'proactive.deleteHabitFailed')));
      }).catch(() => {});
    },
    enumLabel(group, value) {
      if (!value) return '-';
      const key = `proactive.${group}.${value}`;
      const translated = this.$t(key);
      return translated === key ? value : translated;
    },
    habitStatus(habit) {
      if (habit.accepted) return this.$t('proactive.habitStatus.accepted');
      if (habit.dismissed) return this.$t('proactive.habitStatus.dismissed');
      if (habit.suggested) return this.$t('proactive.habitStatus.suggested');
      return this.$t('proactive.habitStatus.observed');
    },
    formatTime(value) {
      if (!value) return '-';
      const date = new Date(value);
      return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString();
    },
  },
};
</script>

<style lang="scss" scoped>
.device-context {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0 14px;
  border-bottom: 1px solid #ebeef5;
  color: #606266;

  strong { margin-right: 12px; color: #303133; }
  span { font-size: 13px; }
}

.section-body { min-height: 430px; }
.settings-body { position: relative; padding: 12px 24px 0 0; }
.silent-notice {
  margin: 0 0 16px 150px;
  padding: 9px 12px;
  border-left: 3px solid #e6a23c;
  background: #fdf6ec;
  color: #8a5a14;
  font-size: 13px;
}
.field-help, .inline-help { color: #909399; font-size: 12px; }
.field-help { margin-top: 4px; line-height: 18px; }
.inline-help { margin-left: 10px; }
.time-separator { margin: 0 8px; color: #909399; }
.settings-actions { padding: 12px 0 0 150px; border-top: 1px solid #ebeef5; }
.filter-row { display: flex; gap: 10px; margin-bottom: 14px; }
.filter-row .el-select { width: 190px; }
.pagination-row { display: flex; justify-content: flex-end; padding-top: 16px; }
.delete-button { color: #f56c6c; }

::v-deep .el-dialog__body { padding: 12px 24px 20px; }
::v-deep .el-tabs__header { margin: 0 0 18px; }
::v-deep .el-checkbox { margin-right: 22px; }
</style>
