<template>
  <div class="welcome">
    <HeaderBar />
    <div class="main-wrapper">
      <div class="content-panel">
        <div class="content-area">
          <el-card class="classifier-card" shadow="never">
            <div class="classifier-header">
              <div>
                <h3>{{ $t('paramManagement.classifierTitle') }}</h3>
                <p>{{ $t('paramManagement.classifierDescription') }}</p>
              </div>
              <el-tag :type="classifierAvailable ? 'success' : 'danger'">
                {{ $t(classifierAvailable
                  ? 'paramManagement.classifierAvailable'
                  : 'paramManagement.classifierUnavailable') }}
              </el-tag>
            </div>
            <el-alert v-if="classifierError" :title="classifierError" type="error" :closable="false" show-icon />
            <el-alert v-if="classifierModelsError" :title="classifierModelsError" type="error" :closable="false" show-icon />
            <div class="classifier-controls" v-loading="classifierLoading">
              <el-select v-model="classifierModelId" filterable
                :placeholder="$t('paramManagement.classifierPlaceholder')" class="classifier-select">
                <el-option v-for="model in classifierModels" :key="model.value"
                  :label="model.label" :value="model.value" />
              </el-select>
              <CustomButton type="confirm" :loading="classifierSaving" @click="saveClassifierModel">
                {{ $t('paramManagement.classifierSave') }}
              </CustomButton>
              <CustomButton :loading="classifierTesting" :disabled="!classifierPersistedId || classifierModelId !== classifierPersistedId"
                @click="testClassifierModel">
                {{ $t('paramManagement.classifierTest') }}
              </CustomButton>
            </div>
            <div class="classifier-help">{{ $t('paramManagement.classifierNoFallback') }}</div>
          </el-card>
          <el-card class="params-card" shadow="never">
            <div class="operation-header">
              <h2 class="page-title">{{ $t('paramManagement.pageTitle') }}</h2>
              <div class="right-operations">
                <el-input :placeholder="$t('paramManagement.searchPlaceholder')" v-model="searchCode" class="search-input"
                  @keyup.enter.native="handleSearch" clearable />
                <CustomButton icon="el-icon-search" type="confirm" @click="handleSearch">{{ $t('paramManagement.search') }}</CustomButton>
              </div>
            </div>
            <CustomTable
              ref="paramsTable"
              :data="paramsList"
              :columns="tableColumns"
              :loading="loading"
              :show-selection="true"
              :show-operations="true"
              :operations-label="$t('paramManagement.operation')"
              :total="total"
              :current-page="currentPage"
              :page-size="pageSize"
              :page-size-options="pageSizeOptions"
              :hide-text="$t('paramManagement.hide')"
              :view-text="$t('paramManagement.view')"
              @size-change="handlePageSizeChange"
              @page-change="goToPage"
            >
              <!-- 选择列自定义插槽 -->
              <template slot="selection" slot-scope="scope">
                <el-checkbox v-model="scope.row.selected"></el-checkbox>
              </template>
              <template slot="paramValue" slot-scope="scope">
                <div v-if="isSensitiveParam(scope.row.paramCode)">
                  <span v-if="!scope.row.showValue">
                    {{ maskSensitiveValue(scope.row.paramValue) }}
                  </span>
                  <span v-else>{{ scope.row.paramValue }}</span>
                  <el-button size="mini" type="text" @click="toggleSensitiveValue(scope.row)">
                    {{ scope.row.showValue ? $t('paramManagement.hide') : $t('paramManagement.view') }}
                  </el-button>
                </div>
                <span v-else>{{ scope.row.paramValue }}</span>
              </template>
              <!-- 操作列插槽 -->
              <template slot="operations" slot-scope="scope">
                <el-button size="mini" type="text" @click="editParam(scope.row)">
                  {{ $t('paramManagement.edit') }}
                </el-button>
                <el-button size="mini" type="text" @click="deleteParam(scope.row)">
                  {{ $t('paramManagement.delete') }}
                </el-button>
              </template>
              <template slot="footer-btns">
                <div class="ctrl_btn">
                  <CustomButton :icon="isAllSelected ? 'el-icon-circle-close' : 'el-icon-circle-check'" size="small" @click="handleSelectAll">
                    {{ isAllSelected ? $t('paramManagement.deselectAll') : $t('paramManagement.selectAll') }}
                  </CustomButton>
                  <CustomButton icon="el-icon-plus" type="add" size="small" @click="showAddDialog">
                    {{ $t('paramManagement.add') }}
                  </CustomButton>
                  <CustomButton size="small" type="delete" icon="el-icon-delete" @click="deleteSelectedParams">
                    {{ $t('paramManagement.delete') }}
                  </CustomButton>
                </div>
              </template>
            </CustomTable>
          </el-card>
        </div>
      </div>
    </div>

    <!-- 新增/编辑参数对话框 -->
    <param-dialog
      ref="paramDialog"
      :title="dialogTitle"
      :visible.sync="dialogVisible"
      :form="paramForm"
      @submit="handleSubmit"
      @cancel="dialogVisible = false"
    />
    <el-footer>
      <version-footer />
    </el-footer>
  </div>
</template>

<script>
import Api from "@/apis/api";
import HeaderBar from "@/components/HeaderBar.vue";
import ParamDialog from "@/components/ParamDialog.vue";
import VersionFooter from "@/components/VersionFooter.vue";
import CustomButton from "@/components/CustomButton.vue";
import CustomTable from "@/components/CustomTable.vue";
import CustomDialog from "@/components/CustomDialog.vue";
import { classifierModelId, validClassifierModelId } from '@/utils/proactiveAssistant.mjs';

export default {
  components: { HeaderBar, ParamDialog, VersionFooter, CustomButton, CustomTable, CustomDialog },
  data() {
    return {
      searchCode: "",
      paramsList: [],
      currentPage: 1,
      loading: false,
      pageSize: 10,
      pageSizeOptions: [10, 20, 50, 100],
      total: 0,
      dialogVisible: false,
      dialogTitle: "新增参数",
      isAllSelected: false,
      sensitive_keys: ["api_key", "personal_access_token", "access_token", "token", "secret", "access_key_secret", "secret_key", "password", "mqtt_signature_key", "private_key"],
      paramForm: {
        id: null,
        paramCode: "",
        paramValue: "",
        valueType: "string",
        remark: ""
      },
      tableColumns: [],
      classifierModelId: '',
      classifierPersistedId: '',
      classifierModels: [],
      classifierAvailable: false,
      classifierLoading: false,
      classifierSaving: false,
      classifierTesting: false,
      classifierError: '',
      classifierModelsError: ''
    };
  },
  created() {
    this.initTableColumns();
    this.fetchParams();
    this.loadClassifierModels();
    this.loadClassifierModel();

  },
  methods: {
    classifierErrorMessage(error, fallbackKey) {
      return error && error.data && error.data.msg ? error.data.msg : this.$t(fallbackKey);
    },
    loadClassifierModels() {
      Api.model.getLlmModelCodeList('', ({ data }) => {
        if (data.code !== 0) {
          this.classifierModelsError = data.msg || this.$t('paramManagement.classifierModelsFailed');
          return;
        }
        this.classifierModelsError = '';
        this.classifierModels = (data.data || []).map(item => ({
          value: item.id,
          label: item.modelName || item.id,
        }));
      }, error => {
        this.classifierModelsError = this.classifierErrorMessage(error, 'paramManagement.classifierModelsFailed');
      });
    },
    loadClassifierModel() {
      this.classifierLoading = true;
      this.classifierError = '';
      Api.proactive.getClassifierModel(response => {
        this.classifierLoading = false;
        const model = response && response.data ? response.data.data : {};
        this.classifierModelId = classifierModelId(model);
        this.classifierPersistedId = this.classifierModelId;
        this.classifierAvailable = Boolean(model && model.available);
      }, error => {
        this.classifierLoading = false;
        this.classifierAvailable = false;
        this.classifierError = this.classifierErrorMessage(error, 'paramManagement.classifierLoadFailed');
      });
    },
    saveClassifierModel() {
      if (this.classifierSaving) return;
      if (!validClassifierModelId(this.classifierModelId)) {
        this.$message.warning(this.$t('paramManagement.classifierRequired'));
        return;
      }
      this.classifierSaving = true;
      this.classifierError = '';
      const selectedId = this.classifierModelId.trim();
      Api.proactive.updateClassifierModel({ model_id: selectedId }, response => {
        this.classifierSaving = false;
        const model = response && response.data ? response.data.data : {};
        this.classifierModelId = classifierModelId(model);
        this.classifierPersistedId = this.classifierModelId;
        this.classifierAvailable = Boolean(model && model.available);
        this.$message.success(this.$t('paramManagement.classifierSaveSuccess'));
      }, error => {
        this.classifierSaving = false;
        this.classifierAvailable = false;
        this.classifierError = this.classifierErrorMessage(error, 'paramManagement.classifierSaveFailed');
        this.$message.error(this.classifierError);
      });
    },
    testClassifierModel() {
      if (this.classifierTesting || this.classifierModelId !== this.classifierPersistedId) return;
      this.classifierTesting = true;
      this.classifierError = '';
      Api.proactive.testClassifierModel(response => {
        this.classifierTesting = false;
        const model = response && response.data ? response.data.data : {};
        this.classifierAvailable = Boolean(model && model.available);
        if (this.classifierAvailable) this.$message.success(this.$t('paramManagement.classifierTestSuccess'));
        else this.$message.error(this.$t('paramManagement.classifierTestUnavailable'));
      }, error => {
        this.classifierTesting = false;
        this.classifierAvailable = false;
        this.classifierError = this.classifierErrorMessage(error, 'paramManagement.classifierTestFailed');
        this.$message.error(this.classifierError);
      });
    },
    initTableColumns() {
      this.tableColumns = [
        {
          prop: 'paramCode',
          label: this.$t('paramManagement.paramCode'),
          align: 'center'
        },
        {
          prop: 'paramValue',
          label: this.$t('paramManagement.paramValue'),
          align: 'center',
          sensitive: true,
          toggleable: true
        },
        {
          prop: 'remark',
          label: this.$t('paramManagement.remark'),
          align: 'center'
        }
      ];
    },
    handlePageSizeChange(val) {
      this.pageSize = val;
      this.currentPage = 1;
      this.fetchParams();
    },
    fetchParams() {
      this.loading = true;
      Api.admin.getParamsList(
        {
          page: this.currentPage,
          limit: this.pageSize,
          paramCode: this.searchCode,
        },
        ({ data }) => {
          this.loading = false;
          if (data.code === 0) {
            this.paramsList = data.data.list.map(item => ({
              ...item,
              valueType: item.valueType || "string",
              selected: false,
              showValue: false
            }));
            this.total = data.data.total;
          } else {
            this.$message.error({
              message: data.msg || this.$t('paramManagement.getParamsListFailed'),
              showClose: true
            });
          }
        }
      );
    },
    handleSearch() {
      this.currentPage = 1;
      this.fetchParams();
    },
    handleSelectAll() {
      this.isAllSelected = !this.isAllSelected;
      this.paramsList.forEach(row => {
        row.selected = this.isAllSelected;
      });
    },
    showAddDialog() {
      this.dialogTitle = this.$t('paramManagement.addParam');
      this.paramForm = {
        id: null,
        paramCode: "",
        paramValue: "",
        valueType: "string", // 默认值
        remark: ""
      };
      this.dialogVisible = true;
    },
    editParam(row) {
      this.dialogTitle = this.$t('paramManagement.editParam');
      this.paramForm = {
        id: row.id,
        paramCode: row.paramCode,
        paramValue: row.paramValue,
        valueType: row.valueType || "string", // 确保有值
        remark: row.remark
      };
      this.dialogVisible = true;
    },
    handleSubmit(form) {
      if (form.id) {
        // 更新参数
        Api.admin.updateParam(form, ({ data }) => {
          this.dialogVisible = false;
          this.fetchParams();
          this.$message.success({
            message: this.$t('paramManagement.updateSuccess'),
            showClose: true
          });
        }, ({ data }) => {
          this.$message.error({
            message: data.msg || this.$t('paramManagement.updateFailed'),
            showClose: true
          });
          // 调用ParamDialog的resetSaving方法重置保存状态
          if (this.$refs.paramDialog && typeof this.$refs.paramDialog.resetSaving === 'function') {
            this.$refs.paramDialog.resetSaving();
          }
        });
      } else {
        // 新增参数
        Api.admin.addParam(form, ({ data }) => {
          if (data.code === 0) {
            this.dialogVisible = false;
            this.fetchParams();
            this.$message.success({
              message: this.$t('paramManagement.addSuccess'),
              showClose: true
            });
          } else {
            this.$message.error({
              message: data.msg || this.$t('paramManagement.addFailed'),
              showClose: true
            });
            // 调用ParamDialog的resetSaving方法重置保存状态
            if (this.$refs.paramDialog && typeof this.$refs.paramDialog.resetSaving === 'function') {
              this.$refs.paramDialog.resetSaving();
            }
          }
        }, ({ data }) => {
          this.$message.error({
            message: data.msg || this.$t('paramManagement.updateFailed'),
            showClose: true
          });
          // 调用ParamDialog的resetSaving方法重置保存状态
          if (this.$refs.paramDialog && typeof this.$refs.paramDialog.resetSaving === 'function') {
            this.$refs.paramDialog.resetSaving();
          }
        });
      }
    },
    deleteSelectedParams() {
      const selectedParams = this.paramsList.filter(row => row.selected);
      if (selectedParams.length === 0) {
        this.$message.warning({
          message: this.$t('paramManagement.selectParamsFirst'),
          showClose: true
        });
        return;
      }
      this.deleteParams(selectedParams);
    },
    deleteParam(row) {
      if (!row.id) {
        this.$message.warning({
          message: this.$t('paramManagement.selectParamsFirst'),
          showClose: true
        });
        return;
      }
      this.deleteParams([row]);
    },
    deleteParams(params) {
      const paramCount = params.length;
      const paramIds = params.map(param => param.id).filter(id => id);
      if (paramIds.length === 0) {
        this.$message.error({
          message: this.$t('paramManagement.invalidParamId'),
          showClose: true
        });
        return;
      }
      this.$confirm(this.$t('paramManagement.confirmBatchDelete', { paramCount }), this.$t('message.warning'), {
        confirmButtonText: this.$t('button.ok'),
        cancelButtonText: this.$t('button.cancel'),
        type: 'warning'
      }).then(() => {
        Api.admin.deleteParam(paramIds, ({ data }) => {
          if (data.code === 0) {
            // 删除后检查是否需要调整页码
            const newTotal = this.total - paramCount;
            const maxPage = Math.max(1, Math.ceil(newTotal / this.pageSize));
            if (this.currentPage > maxPage) {
              this.currentPage = maxPage;
            }
            this.fetchParams();
            this.$message.success({
              message: this.$t('paramManagement.batchDeleteSuccess', { paramCount }),
              showClose: true
            });
          } else {
            this.$message.error({
              message: data.msg || this.$t('paramManagement.deleteFailed'),
              showClose: true
            });
          }
        });
      }).catch(() => {
        this.$message({
          type: 'info',
          message: this.$t('paramManagement.operationCancelled'),
          duration: 1000
        });
      });
    },
    goToPage(page) {
      if (page !== this.currentPage) {
        this.currentPage = page;
        this.fetchParams();
      }
    },
    isSensitiveParam(paramCode) {
      return this.sensitive_keys.some(key => paramCode.toLowerCase().includes(key));
    },
    maskSensitiveValue(value) {
      if (value.length <= 4) {
        return '****';
      }
      return value.substring(0, 2) + '****' + value.substring(value.length - 2);
    },
    toggleSensitiveValue(row) {
      row.showValue = !row.showValue;
    },
  }
};
</script>

<style lang="scss" scoped>
.welcome {
  min-width: 900px;
  min-height: 506px;
  height: 100vh;
  display: flex;
  position: relative;
  flex-direction: column;
  background-size: cover;
  background: #eff4ff;
  -webkit-background-size: cover;
  -o-background-size: cover;
  overflow: hidden;
}

.main-wrapper {
  // 顶部 63px 底部 35px
  height: calc(100vh - 63px - 35px);
  padding: 20px 22px 0;
  position: relative;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
}

.operation-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0 16px 0;
}

.page-title {
  font-weight: 500;
  font-size: 24px;
  margin: 0;
}

.right-operations {
  display: flex;
  gap: 10px;
  margin-left: auto;
}

.search-input {
  width: 240px;
}

.btn-search {
  background: linear-gradient(135deg, #6b8cff, #a966ff);
  border: none;
  color: white;
}

.content-panel {
  display: flex;
  overflow: hidden;
  height: 100%;
  border-radius: 15px;
  background: transparent;
  border: 1px solid #fff;
}

.content-area {
  flex: 1;
  height: 100%;
  min-width: 600px;
  overflow: auto;
  background-color: white;
  display: flex;
  flex-direction: column;
}

.params-card {
  background: white;
  flex: 1;
  display: flex;
  flex-direction: column;
  border: none;
  box-shadow: none;
  overflow: hidden;

  ::v-deep .el-card__body {
    padding: 14px 20px;
    display: flex;
    flex-direction: column;
    flex: 1;
    overflow: hidden;
  }
}

.classifier-card {
  flex: 0 0 auto;
  margin: 14px 20px 0;
  border-color: #e4e7ed;
}

.classifier-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;

  h3 { margin: 0 0 5px; color: #303133; font-size: 16px; }
  p { margin: 0; color: #909399; font-size: 13px; }
}

.classifier-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
}

.classifier-select { width: 360px; max-width: 100%; }
.classifier-help { margin-top: 9px; color: #909399; font-size: 12px; }

.ctrl_btn {
  display: flex;
}

:deep(.el-table .el-button--text) {
  color: #7079aa;
}

:deep(.el-table .el-button--text:hover) {
  color: #5a64b5;
}
</style>
