<template>
  <el-drawer :visible.sync="dialogVisible" direction="rtl" size="80%" :wrapperClosable="false" :withHeader="false">
    <!-- 自定义标题区域 -->
    <div class="custom-header">
      <div class="header-left">
        <h3 class="bold-title">{{ $t('functionDialog.title') }}</h3>
      </div>
      <button class="custom-close-btn" @click="closeDialog">×</button>
    </div>

    <div class="function-manager">
      <!-- 左侧：未选功能 -->
      <div class="function-column">
        <div class="column-header">
          <h4 class="column-title">{{ $t('functionDialog.unselectedFunctions') }}</h4>
          <el-button type="text" @click="selectAll" class="select-all-btn">
            {{ $t('functionDialog.selectAll') }}
          </el-button>
        </div>
        <div class="function-list">
          <div v-if="unselected.length">
            <div v-for="func in unselected" :key="func.name" class="function-item">
              <el-checkbox :label="func.name" v-model="selectedNames" @change="(val) => handleCheckboxChange(func, val)"
                @click.native.stop></el-checkbox>
              <div class="func-tag" @click="handleFunctionClick(func)">
                <div class="color-dot"></div>
                <span>{{ func.name }}</span>
              </div>
            </div>
          </div>
          <div v-else style="display: flex; justify-content: center; align-items: center;">
            <el-empty :description="$t('functionDialog.noMorePlugins')" />
          </div>
        </div>
      </div>

      <!-- 中间：已选功能 -->
      <div class="function-column">
        <div class="column-header">
          <h4 class="column-title">{{ $t('functionDialog.selectedFunctions') }}</h4>
          <el-button type="text" @click="deselectAll" class="select-all-btn">
            {{ $t('functionDialog.selectAll') }}
          </el-button>
        </div>
        <div class="function-list">
          <div v-if="selectedList.length > 0">
            <div v-for="func in selectedList" :key="func.name" class="function-item">
              <el-checkbox :label="func.name" v-model="selectedNames" @change="(val) => handleCheckboxChange(func, val)"
                @click.native.stop></el-checkbox>
              <div class="func-tag" @click="handleFunctionClick(func)">
                <div class="color-dot"></div>
                <span>{{ func.name }}</span>
              </div>
            </div>
          </div>
          <div v-else style="display: flex; justify-content: center; align-items: center;">
            <el-empty :description="$t('functionDialog.pleaseSelectPlugin')" />
          </div>
        </div>
      </div>

      <!-- 右侧：参数配置 -->
      <div class="params-column">
        <h4 v-if="currentFunction" class="column-title">
          {{ $t('functionDialog.paramConfig') }} - {{ currentFunction.name }}
        </h4>
        <div v-if="currentFunction" class="params-container">
          <el-form :model="currentFunction" class="param-form">
            <div v-if="isNeteaseMusicFunction(currentFunction)" class="netease-login-panel">
              <div class="netease-login-status">
                <span>{{ $t('functionDialog.neteaseLoginStatus') }}</span>
                <el-tag :type="neteaseLogin.status === 'loggedIn' ? 'success' : 'info'">
                  {{ neteaseLoginStatusText }}
                </el-tag>
              </div>
              <img v-if="neteaseLogin.qrImage" :src="neteaseLogin.qrImage" class="netease-qr-image"
                :alt="$t('functionDialog.neteaseQrAlt')" />
              <div class="netease-login-actions">
                <el-button type="primary" size="small" :loading="neteaseLogin.loading"
                  @click="startNeteaseQrLogin">
                  {{ $t('functionDialog.neteaseScanLogin') }}
                </el-button>
                <el-button v-if="neteaseLogin.status === 'loggedIn'" size="small" @click="clearNeteaseLogin">
                  {{ $t('functionDialog.neteaseLogout') }}
                </el-button>
              </div>
              <p class="netease-login-tip">{{ $t('functionDialog.neteaseLoginTip') }}</p>
            </div>
            <!-- 遍历 fieldsMeta，而不是 params 的 keys -->
            <div v-if="visibleFunctionFields.length == 0 && !isNeteaseMusicFunction(currentFunction)">
              <el-empty :description="currentFunction.name + $t('functionDialog.noNeedToConfig')" />
            </div>
            <el-form-item v-for="field in visibleFunctionFields" :key="field.key" :label="field.label"
              class="param-item" :class="{ 'textarea-field': field.type === 'array' || field.type === 'json' }">
              <template #label>
                <span style="font-size: 16px; margin-right: 6px;">{{ field.label }}</span>
                <el-tooltip effect="dark" :content="fieldRemark(field)" placement="top">
                  <img src="@/assets/home/info.png" alt="" class="info-icon">
                </el-tooltip>
              </template>
              <!-- ARRAY -->
              <el-input v-if="field.type === 'array'" type="textarea" v-model="currentFunction.params[field.key]"
                @change="val => handleParamChange(currentFunction, field.key, val)" />

              <!-- JSON -->
              <el-input v-else-if="field.type === 'json'" type="textarea" :rows="6" placeholder="请输入合法的 JSON"
                v-model="textCache[field.key]" @blur="flushJson(field)" />

              <!-- number -->
              <el-input-number v-else-if="field.type === 'number'" :value="currentFunction.params[field.key]"
                @change="val => handleParamChange(currentFunction, field.key, val)" />

              <!-- boolean -->
              <el-switch v-else-if="field.type === 'boolean' || field.type === 'bool'"
                :value="currentFunction.params[field.key]"
                @change="val => handleParamChange(currentFunction, field.key, val)" />

              <!-- string or fallback -->
              <el-input v-else v-model="currentFunction.params[field.key]"
                @change="val => handleParamChange(currentFunction, field.key, val)" />
            </el-form-item>
          </el-form>
        </div>
        <div v-else class="empty-tip">{{ $t('functionDialog.pleaseSelectFunctionForParam') }}</div>
      </div>
    </div>

    <!-- MCP区域 -->
    <div class="mcp-access-point" v-if="featureStatus.mcpAccessPoint">
      <div class="mcp-container">
        <!-- 左侧区域 -->
        <div class="mcp-left">
          <div class="mcp-header">
            <h3 class="bold-title">{{ $t('functionDialog.mcpAccessPoint') }}</h3>
          </div>
          <div class="url-header">
            <div class="address-desc">
              <span>{{ $t('functionDialog.mcpAddressDesc') }}</span>
              <a href="https://github.com/xinnan-tech/xiaozhi-esp32-server/blob/main/docs/mcp-endpoint-enable.md"
                target="_blank" class="doc-link">{{ $t('functionDialog.howToDeployMcp') }}</a> &nbsp;&nbsp;|&nbsp;&nbsp;
              <a href="https://github.com/xinnan-tech/xiaozhi-esp32-server/blob/main/docs/mcp-endpoint-integration.md"
                target="_blank" class="doc-link">{{ $t('functionDialog.howToIntegrateMcp') }}</a> &nbsp;
            </div>
          </div>
          <el-input v-model="mcpUrl" readonly class="url-input">
            <template #suffix>
              <el-button @click="copyUrl" class="inner-copy-btn" icon="el-icon-document-copy">
                {{ $t('functionDialog.copy') }}
              </el-button>
            </template>
          </el-input>
        </div>

        <!-- 右侧区域 -->
        <div class="mcp-right">
          <div class="mcp-header">
            <h3 class="bold-title">{{ $t('functionDialog.accessPointStatus') }}</h3>
          </div>
          <div class="status-container">
            <span class="status-indicator" :class="mcpStatus"></span>
            <span class="status-text">{{
              mcpStatus === 'connected' ? $t('functionDialog.connected') :
                mcpStatus === 'loading' ? $t('functionDialog.loading') : $t('functionDialog.disconnected')
            }}</span>
            <button class="refresh-btn" @click="refreshStatus">
              <span class="refresh-icon">↻</span>
              <span>{{ $t('functionDialog.refresh') }}</span>
            </button>
          </div>
          <div class="mcp-tools-list">
            <div v-if="mcpTools.length > 0" class="tools-grid">
              <el-button v-for="tool in mcpTools" :key="tool" size="small" class="tool-btn" plain>
                {{ tool }}
              </el-button>
            </div>
            <div v-else class="no-tools">
              <span>{{ $t('functionDialog.noAvailableTools') }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="drawer-footer">
      <el-button @click="closeDialog">{{ $t('functionDialog.cancel') }}</el-button>
      <el-button type="primary" @click="saveSelection">{{ $t('functionDialog.saveConfig') }}</el-button>
    </div>
  </el-drawer>
</template>

<script>
import Api, { getServiceUrl } from '@/apis/api';
import i18n from '@/i18n';
import featureManager from '@/utils/featureManager';
import {
  checkNeteaseQrLogin,
  createNeteaseQrLogin,
  getNeteaseLoginProfile,
  resolveNeteaseLoginApiBaseUrl,
} from '@/utils/neteaseMusicLogin.mjs';

const MUSIC_PROVIDER_CODES = new Set(['play_music', 'play_netease_music', 'hass_play_music']);

export default {
  i18n,

  props: {
    value: Boolean,
    functions: {
      type: Array,
      default: () => []
    },
    allFunctions: {
      type: Array,
      default: () => []
    },
    agentId: {
      type: String,
      required: true
    }
  },
  data() {
    return {
      textCache: {},
      dialogVisible: this.value,
      selectedNames: [],
      currentFunction: null,
      modifiedFunctions: {},
      tempFunctions: {},
      // 添加一个标志位来跟踪是否已经保存
      hasSaved: false,
      loading: false,
      neteaseLogin: {
        status: 'anonymous',
        nickname: '',
        qrImage: '',
        qrKey: '',
        loading: false,
        pollTimer: null,
        polling: false,
        abortController: null,
        profileAbortController: null
      },
      neteaseOriginalCookies: {},

      mcpUrl: "",
      mcpStatus: "disconnected",
      mcpTools: [],
      
      // 功能状态
      featureStatus: {
        mcpAccessPoint: false,
        addressBook: false
      }
    }
  },
  computed: {
    selectedList() {
      const list = this.allFunctions.filter(f => this.selectedNames.includes(f.name));
      // 如果通讯录功能未启用，过滤掉设备呼叫设备插件
      if (!this.featureStatus.addressBook) {
        return list.filter(f => f.providerCode !== 'call_device');
      }
      return list;
    },
    unselected() {
      const list = this.allFunctions.filter(f => !this.selectedNames.includes(f.name));
      // 如果通讯录功能未启用，过滤掉设备呼叫设备插件
      if (!this.featureStatus.addressBook) {
        return list.filter(f => f.providerCode !== 'call_device');
      }
      return list;
    },
    visibleFunctionFields() {
      return this.currentFunction?.fieldsMeta?.filter(field => field.type !== 'secret') || [];
    },
    neteaseLoginStatusText() {
      if (this.neteaseLogin.status === 'loggedIn') {
        return this.neteaseLogin.nickname || this.$t('functionDialog.neteaseLoggedIn');
      }
      if (this.neteaseLogin.status === 'waiting') {
        return this.$t('functionDialog.neteaseWaitingScan');
      }
      if (this.neteaseLogin.status === 'expired') {
        return this.$t('functionDialog.neteaseQrExpired');
      }
      return this.$t('functionDialog.neteaseAnonymous');
    }
  },
  watch: {
    currentFunction(newFn) {
      this.stopNeteaseQrPolling();
      this.resetNeteaseLoginState();
      if (!newFn) return;
      // 对每个字段，如果是 array 或 json，就在 textCache 里生成初始字符串
      newFn.fieldsMeta.forEach(f => {
        const v = newFn.params[f.key];
        if (f.type === 'array') {
          this.$set(this.textCache, f.key, Array.isArray(v) ? v.join('\n') : '');
        }
        else if (f.type === 'json') {
          try {
            this.$set(this.textCache, f.key, JSON.stringify(v ?? {}, null, 2));
          } catch {
            this.$set(this.textCache, f.key, '');
          }
        }
      });
      if (this.isNeteaseMusicFunction(newFn)) {
        if (!(newFn.name in this.neteaseOriginalCookies)) {
          this.$set(this.neteaseOriginalCookies, newFn.name, newFn.params?.cookie || '');
        }
        this.loadNeteaseLoginStatus(newFn);
      }
    },
    async value(v) {
      this.dialogVisible = v;
      if (v) {
        // 加载功能状态（需要在初始化选中态之前）
        await this.loadFeatureStatus();

        // 对话框打开时，初始化选中态
        this.selectedNames = this.functions.map(f => f.name);

        // 如果通讯录功能未启用，从已选列表中移除设备呼叫设备插件
        if (!this.featureStatus.addressBook) {
          this.selectedNames = this.selectedNames.filter(name => {
            const func = this.allFunctions.find(f => f.name === name);
            return func && func.providerCode !== 'call_device';
          });
        }

        // 把后端传来的 this.functions（带 params）merge 到 allFunctions 上
        this.functions.forEach(saved => {
          const idx = this.allFunctions.findIndex(f => f.name === saved.name);
          if (idx >= 0) {
            // 保留用户之前在 saved.params 上的改动
            this.allFunctions[idx].params = { ...saved.params };
          }
        });
        // 右侧默认指向第一个
        this.currentFunction = this.selectedList[0] || null;

        // 加载MCP数据
        this.loadMcpAddress();
        this.loadMcpTools();
      }
    },
    dialogVisible(newVal) {
      this.$emit('input', newVal);
    }
  },
  beforeDestroy() {
    this.stopNeteaseQrPolling();
  },
  methods: {
    isNeteaseMusicFunction(func) {
      return func?.providerCode === 'play_netease_music';
    },
    isMusicFunction(func) {
      return MUSIC_PROVIDER_CODES.has(func?.providerCode);
    },
    currentNeteaseApiBaseUrl(func = this.currentFunction) {
      return func?.params?.api_base_url || '';
    },
    currentNeteaseLoginApiBaseUrl(func = this.currentFunction) {
      return resolveNeteaseLoginApiBaseUrl(this.currentNeteaseApiBaseUrl(func), getServiceUrl());
    },
    async fetchNeteaseLoginApi(url, options) {
      const proxyBaseUrl = `${String(getServiceUrl() || '').replace(/\/+$/, '')}/models/provider/plugin/netease-login`;
      if (!String(url).startsWith(proxyBaseUrl)) {
        return fetch(url, options);
      }

      let accessToken;
      try {
        accessToken = JSON.parse(this.$store?.getters?.getToken || 'null')?.token;
      } catch (error) {
        throw new Error('智控台登录状态无效，请重新登录后再扫码');
      }
      if (!accessToken) {
        throw new Error('智控台登录已失效，请重新登录后再扫码');
      }
      const headers = new Headers(options?.headers || {});
      headers.set('Authorization', `Bearer ${accessToken}`);
      return fetch(url, { ...options, headers });
    },
    resetNeteaseLoginState() {
      this.neteaseLogin.status = 'anonymous';
      this.neteaseLogin.nickname = '';
      this.neteaseLogin.qrImage = '';
      this.neteaseLogin.qrKey = '';
      this.neteaseLogin.loading = false;
    },
    stopNeteaseQrPolling() {
      if (this.neteaseLogin.pollTimer) {
        clearInterval(this.neteaseLogin.pollTimer);
        this.neteaseLogin.pollTimer = null;
      }
      if (this.neteaseLogin.abortController) {
        this.neteaseLogin.abortController.abort();
        this.neteaseLogin.abortController = null;
      }
      if (this.neteaseLogin.profileAbortController) {
        this.neteaseLogin.profileAbortController.abort();
        this.neteaseLogin.profileAbortController = null;
      }
      this.neteaseLogin.polling = false;
    },
    async loadNeteaseLoginStatus(func = this.currentFunction) {
      const cookie = func?.params?.cookie;
      if (!cookie) {
        this.neteaseLogin.status = 'anonymous';
        return;
      }
      if (this.neteaseLogin.profileAbortController) {
        this.neteaseLogin.profileAbortController.abort();
      }
      const controller = new AbortController();
      this.neteaseLogin.profileAbortController = controller;
      try {
        const profile = await getNeteaseLoginProfile(
          this.currentNeteaseLoginApiBaseUrl(func),
          cookie,
          (url, options) => this.fetchNeteaseLoginApi(url, options),
          controller.signal
        );
        if (this.neteaseLogin.profileAbortController !== controller) return;
        if (this.currentFunction !== func || func.params?.cookie !== cookie) return;
        if (!profile) {
          this.neteaseLogin.status = 'expired';
          return;
        }
        this.neteaseLogin.status = 'loggedIn';
        this.neteaseLogin.nickname = profile.nickname || '';
      } catch (error) {
        if (this.neteaseLogin.profileAbortController !== controller) return;
        if (this.currentFunction !== func || func.params?.cookie !== cookie) return;
        this.neteaseLogin.status = 'expired';
      } finally {
        if (this.neteaseLogin.profileAbortController === controller) {
          this.neteaseLogin.profileAbortController = null;
        }
      }
    },
    async startNeteaseQrLogin() {
      this.stopNeteaseQrPolling();
      this.neteaseLogin.loading = true;
      const controller = new AbortController();
      this.neteaseLogin.abortController = controller;
      try {
        const result = await createNeteaseQrLogin(
          this.currentNeteaseLoginApiBaseUrl(),
          (url, options) => this.fetchNeteaseLoginApi(url, options),
          controller.signal
        );
        if (this.neteaseLogin.abortController !== controller) return;
        this.neteaseLogin.abortController = null;
        this.neteaseLogin.qrKey = result.key;
        this.neteaseLogin.qrImage = result.qrImage;
        this.neteaseLogin.status = 'waiting';
        this.neteaseLogin.pollTimer = setInterval(() => this.pollNeteaseQrLogin(), 2000);
        await this.pollNeteaseQrLogin();
      } catch (error) {
        if (this.neteaseLogin.abortController !== controller) return;
        this.neteaseLogin.abortController = null;
        this.neteaseLogin.status = 'anonymous';
        this.$message.error(error.message || this.$t('functionDialog.neteaseLoginFailed'));
      } finally {
        this.neteaseLogin.loading = false;
      }
    },
    async pollNeteaseQrLogin() {
      if (
        !this.neteaseLogin.qrKey ||
        this.neteaseLogin.status !== 'waiting' ||
        this.neteaseLogin.polling
      ) {
        return;
      }
      const qrKey = this.neteaseLogin.qrKey;
      const func = this.currentFunction;
      const controller = new AbortController();
      this.neteaseLogin.abortController = controller;
      this.neteaseLogin.polling = true;
      try {
        const payload = await checkNeteaseQrLogin(
          this.currentNeteaseLoginApiBaseUrl(func),
          qrKey,
          (url, options) => this.fetchNeteaseLoginApi(url, options),
          controller.signal
        );
        if (this.neteaseLogin.abortController !== controller) return;
        if (this.currentFunction !== func || this.neteaseLogin.qrKey !== qrKey) return;
        if (payload.code === 800) {
          this.neteaseLogin.status = 'expired';
          this.stopNeteaseQrPolling();
          return;
        }
        if (payload.code !== 803 || !payload.cookie) {
          return;
        }
        this.stopNeteaseQrPolling();
        this.$set(this.currentFunction.params, 'cookie', payload.cookie);
        this.handleParamChange(this.currentFunction, 'cookie', payload.cookie);
        this.neteaseLogin.qrImage = '';
        await this.loadNeteaseLoginStatus(this.currentFunction);
        this.$message.success(this.$t('functionDialog.neteaseLoginSuccess'));
      } catch (error) {
        if (this.neteaseLogin.abortController !== controller) return;
        if (this.currentFunction !== func || this.neteaseLogin.qrKey !== qrKey) return;
        this.stopNeteaseQrPolling();
        this.neteaseLogin.status = 'anonymous';
        this.$message.error(error.message || this.$t('functionDialog.neteaseLoginFailed'));
      } finally {
        if (this.neteaseLogin.abortController === controller) {
          this.neteaseLogin.abortController = null;
          this.neteaseLogin.polling = false;
        }
      }
    },
    clearNeteaseLogin() {
      this.stopNeteaseQrPolling();
      this.$set(this.currentFunction.params, 'cookie', '');
      this.handleParamChange(this.currentFunction, 'cookie', '');
      this.resetNeteaseLoginState();
      this.$message.success(this.$t('functionDialog.neteaseLogoutSuccess'));
    },
    /**
     * 加载功能状态
     */
    async loadFeatureStatus() {
      // 确保featureManager已初始化完成
      await featureManager.waitForInitialization();

      const config = featureManager.getConfig();
      this.featureStatus = {
        mcpAccessPoint: config.mcpAccessPoint || false,
        addressBook: config.addressBook || false
      };
    },
    
    copyUrl() {
      const textarea = document.createElement('textarea');
      textarea.value = this.mcpUrl;
      textarea.style.position = 'fixed';  // 防止页面滚动
      document.body.appendChild(textarea);
      textarea.select();

      try {
        const successful = document.execCommand('copy');
        if (successful) {
          this.$message.success(this.$t('functionDialog.copiedToClipboard'));
        } else {
          this.$message.error(this.$t('functionDialog.copyFailed'));
        }
      } catch (err) {
        this.$message.error('复制失败，请手动复制');
        console.error('复制失败:', err);
      } finally {
        document.body.removeChild(textarea);
      }
    },

    refreshStatus() {
      this.mcpStatus = "loading";
      this.loadMcpTools();
    },

    // 加载MCP接入点地址
    loadMcpAddress() {
      Api.agent.getAgentMcpAccessAddress(this.agentId, (res) => {
        if (res.data.code === 0) {
          this.mcpUrl = res.data.data || "";
        } else {
          this.mcpUrl = res.data.msg;
          console.error('获取MCP地址失败:', res.data.msg);
        }
      });
    },

    // 加载MCP工具列表
    loadMcpTools() {
      Api.agent.getAgentMcpToolsList(this.agentId, (res) => {
        if (res.data.code === 0) {
          this.mcpTools = res.data.data || [];
          // 根据工具列表更新状态
          this.mcpStatus = this.mcpTools.length > 0 ? "connected" : "disconnected";
        } else {
          this.mcpTools = [];
          this.mcpStatus = "disconnected";
          console.error('获取MCP工具列表失败:', res.data.msg);
        }
      });
    },

    flushArray(key) {
      const text = this.textCache[key] || '';
      const arr = text
        .split('\n')
        .map(s => s.trim())
        .filter(Boolean);
      this.handleParamChange(this.currentFunction, key, arr);
    },

    flushJson(field) {
      const key = field.key;
      if (!key) {
        return;
      }
      const text = this.textCache[key] || '';
      try {
        const obj = JSON.parse(text);
        this.handleParamChange(this.currentFunction, key, obj);
      } catch {
        this.$message.error(`${this.currentFunction.name}${this.$t('functionDialog.jsonFormatError')}`);
      }
    },
    handleFunctionClick(func) {
      if (this.selectedNames.includes(func.name)) {
        const tempFunc = this.tempFunctions[func.name];
        this.currentFunction = tempFunc ? tempFunc : func;
      }
    },
    handleParamChange(func, key, value) {
      if (!this.tempFunctions[func.name]) {
        this.tempFunctions[func.name] = JSON.parse(JSON.stringify(func));
      }
      this.tempFunctions[func.name].params[key] = value;
    },
    handleCheckboxChange(func, checked) {
      if (checked) {
        if (this.isMusicFunction(func)) {
          this.selectedNames = this.selectedNames.filter(name => {
            const selected = this.allFunctions.find(item => item.name === name);
            return !this.isMusicFunction(selected) || selected.name === func.name;
          });
        }
        if (!this.selectedNames.includes(func.name)) {
          this.selectedNames = [...this.selectedNames, func.name];
        }
      } else {
        this.selectedNames = this.selectedNames.filter(name => name !== func.name);
      }

      if (this.selectedList.length > 0) {
        this.currentFunction = this.selectedList[0];
      } else {
        this.currentFunction = null;
      }
    },

    selectAll() {
      const currentMusic = this.selectedList.find(func => this.isMusicFunction(func))
        || this.allFunctions.find(func => func.providerCode === 'play_music')
        || this.allFunctions.find(func => this.isMusicFunction(func));
      this.selectedNames = this.allFunctions
        .filter(func => !this.isMusicFunction(func) || func.name === currentMusic?.name)
        .map(func => func.name);
      if (this.selectedList.length > 0) {
        this.currentFunction = JSON.parse(JSON.stringify(this.selectedList[0]));
      }
    },

    deselectAll() {
      this.selectedNames = [];
      this.currentFunction = null;
    },

    closeDialog() {
      this.stopNeteaseQrPolling();
      Object.entries(this.neteaseOriginalCookies).forEach(([name, cookie]) => {
        const func = this.allFunctions.find(item => item.name === name);
        if (func?.params) {
          this.$set(func.params, 'cookie', cookie);
        }
      });
      this.neteaseOriginalCookies = {};
      this.tempFunctions = {};
      this.selectedNames = this.functions.map(f => f.name);
      this.currentFunction = null;
      this.dialogVisible = false;
      this.$emit('input', false);
      this.$emit('dialog-closed', false);
    },

    saveSelection() {
      this.stopNeteaseQrPolling();
      this.neteaseOriginalCookies = {};
      Object.keys(this.tempFunctions).forEach(name => {
        this.modifiedFunctions[name] = JSON.parse(JSON.stringify(this.tempFunctions[name]));
      });
      this.tempFunctions = {};
      this.hasSaved = true;

      let selected = this.selectedList.map(f => {
        const modified = this.modifiedFunctions[f.name];
        return {
          id: f.id,
          name: f.name,
          params: modified
            ? { ...modified.params }
            : { ...f.params }
        }
      });

      // 如果通讯录功能未启用，自动取消已选的设备呼叫设备插件
      if (!this.featureStatus.addressBook) {
        selected = selected.filter(f => f.providerCode !== 'call_device');
      }

      this.$emit('update-functions', selected);
      this.dialogVisible = false;
      // 通知父组件对话框已关闭且已保存
      this.$emit('dialog-closed', true);
    },
    fieldRemark(field) {
      let description = (field && field.label) ? field.label : '';
      if (field.default) {
        description += `（${this.$t('functionDialog.defaultValue')}：${field.default}）`;
      }
      return description;
    },
  }
}
</script>

<style lang="scss" scoped>
.function-manager {
  display: grid;
  grid-template-columns: max-content max-content 1fr;
  gap: 12px;
  height: calc(58vh);
}

.custom-header {
  position: relative;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid #EBEEF5;

  .header-left {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .bold-title {
    font-size: 18px;
    font-weight: bold;
    margin: 0;
  }

  .select-all-btn {
    padding: 0;
    height: auto;
    font-size: 14px;
  }
}

.function-column {
  position: relative;
  display: flex;
  flex-direction: column;
  width: auto;
  height: 100%; 
  padding: 10px;
  border-right: 1px solid #EBEEF5;
  scrollbar-width: none;
  overflow-x: hidden;
  box-sizing: border-box;
}

.mcp-access-point {
  position: relative;
  z-index: 1;
  background: white;
}

.function-column::-webkit-scrollbar {
  display: none;
}

.function-list {
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: thin;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.function-item {
  padding: 8px 12px;
  margin: 4px 0;
  width: 100%;
  text-align: left;
  cursor: pointer;
  border-radius: 4px;
  transition: background-color 0.2s;
  display: flex;
  align-items: center;
  justify-content: space-between;

  &:hover {
    background-color: #f5f7fa;
  }
}

.params-column {
  min-width: 280px;
  padding: 10px;
  overflow-y: auto;
  scrollbar-width: none;
}

.params-column::-webkit-scrollbar {
  display: none;
}

.column-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.column-title {
  text-align: center;
  width: 100%;
}

.func-tag {
  display: flex;
  align-items: center;
  cursor: pointer;
  flex-grow: 1;
  margin-left: 8px;
}

.color-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  background-color: #5778ff;
  margin-right: 8px;
  border-radius: 50%;
}

.param-form {
  .param-item {
    font-size: 16px;

    &.textarea-field {
      ::v-deep .el-form-item__content {
        margin-left: 0 !important;
        display: block;
        width: 100%;
      }

      ::v-deep .el-form-item__label {
        display: block;
        width: 100% !important;
        margin-bottom: 8px;
      }
    }
  }

  .param-input {
    width: 100%;
  }

  ::v-deep .el-form-item {
    display: flex;
    flex-direction: column;
    margin-bottom: 12px;

    .el-form-item__label {
      font-size: 14px !important;
      color: #606266;
      text-align: left;
      padding-right: 10px;
      flex-shrink: 0;
      width: auto !important;
    }

    .el-form-item__content {
      margin-left: 0 !important;
      flex-grow: 1;

      .el-input__inner {
        text-align: left;
        padding-left: 8px;
        width: 100%;
      }
    }
  }
}

.netease-login-panel {
  padding: 14px;
  margin-bottom: 16px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fafafa;
}

.netease-login-status,
.netease-login-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.netease-qr-image {
  display: block;
  width: 180px;
  height: 180px;
  margin: 14px auto;
}

.netease-login-tip {
  margin: 10px 0 0;
  color: #909399;
  font-size: 13px;
  line-height: 1.5;
}

.params-container {
  padding: 16px;
  border-radius: 4px;
  min-width: 280px;
}

.empty-tip {
  padding: 20px;
  color: #909399;
  text-align: center;
}


.drawer-footer {
  position: absolute;
  bottom: 0;
  width: 100%;
  border-top: 1px solid #e8e8e8;
  padding: 10px 16px;
  text-align: center;
  background: #fff;
}

.info-icon {
  width: 16px;
  height: 16px;
  margin-right: 1vh;
}

.custom-close-btn {
  position: absolute;
  top: 50%;
  right: 10px;
  transform: translateY(-50%);
  width: 35px;
  height: 35px;
  border-radius: 50%;
  border: 2px solid #cfcfcf;
  background: none;
  font-size: 30px;
  font-weight: lighter;
  color: #cfcfcf;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1;
  padding: 0;
  outline: none;
  transition: all 0.3s;
}

.custom-close-btn:hover {
  color: #409EFF;
  border-color: #409EFF;
}

::v-deep .el-checkbox__label {
  display: none;
}

.mcp-access-point {
  border-top: 1px solid #EBEEF5;
  padding: 20px 24px;
  text-align: left;
}

.mcp-header {
  .bold-title {
    font-size: 18px;
    font-weight: bold;
    margin: 5px 0 30px 0;
  }
}

.mcp-container {
  display: flex;
  justify-content: space-between;
  gap: 30px;
}

.mcp-left,
.mcp-right {
  flex: 1;
  padding-bottom: 50px;
}

.url-header {
  margin-bottom: 8px;
  color: black;

  h4 {
    margin: 0 0 15px 0;
    font-size: 16px;
    font-weight: normal;
  }

  .address-desc {
    display: flex;
    align-items: center;
    font-size: 14px;
    margin-bottom: 12px;

    .doc-link {
      color: #1677ff;
      text-decoration: none;
      margin-left: 4px;

      &:hover {
        text-decoration: underline;
      }
    }
  }
}

.url-input {
  border-radius: 4px 0 0 4px;
  font-size: 14px;
  height: 36px;
  box-sizing: border-box;

  ::v-deep .el-input__inner {
    background-color: #f5f5f5 !important;
  }

  ::v-deep .el-input__suffix {
    right: 0;
    display: flex;
    align-items: center;
    padding-right: 10px;

    .inner-copy-btn {
      pointer-events: auto;
      border: none;
      background: #1677ff;
      color: white;
      padding: 6px;
      margin-top: 4px;
      margin-left: 4px;
    }
  }
}

.mcp-right {
  h4 {
    margin: 0 0 10px 0;
    font-size: 16px;
    font-weight: normal;
    color: black;
  }
}

.status-container {
  display: flex;
  align-items: center;

  .status-indicator {
    display: inline-block;
    width: 8px;
    height: 8px;
    border-radius: 50%;
    margin-right: 8px;

    &.disconnected {
      background-color: #909399;
      /* 灰色 - 未连接 */
    }

    &.connected {
      background-color: #67C23A;
      /* 绿色 - 已连接 */
    }

    &.loading {
      background-color: #E6A23C;
      /* 橙色 - 加载中 */
      animation: pulse 1.5s infinite;
    }
  }

  .status-text {
    font-size: 14px;
    margin-right: 10px;
  }

  .refresh-btn {
    display: flex;
    align-items: center;
    padding: 2px 10px;
    background: white;
    color: black;
    border: 1px solid #DCDFE6;
    border-radius: 4px;
    cursor: pointer;
    font-size: 14px;
    transition: all 0.3s;

    &:hover {
      background: #1677ff;
      color: white;
      border-color: #1677ff;
    }

    .refresh-icon {
      margin-right: 6px;
      font-size: 14px;
    }
  }
}

@keyframes pulse {
  0% {
    opacity: 1;
  }

  50% {
    opacity: 0.4;
  }

  100% {
    opacity: 1;
  }
}

.mcp-tools-list {
  margin-top: 10px;

  .tools-grid {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .tool-btn {
    padding: 6px 12px;
    border-color: #1677ff;
    color: #1677ff;
    background-color: white;
    font-size: 12px;

    &:hover {
      background-color: #1677ff;
      color: white;
      border-color: #1677ff;
    }
  }

  .no-tools {
    text-align: center;
    color: #909399;
    font-size: 14px;
    padding: 10px 0;
  }
}
</style>
