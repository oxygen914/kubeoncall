(() => {
    "use strict";

    const elements = {
        baseUrl: document.querySelector("#base-url"),
        token: document.querySelector("#api-token"),
        toggleToken: document.querySelector("#toggle-token"),
        connectionForm: document.querySelector("#connection-form"),
        connectionState: document.querySelector("#connection-state"),
        connectionLabel: document.querySelector("#connection-label"),
        refreshOverview: document.querySelector("#refresh-overview"),
        metricStatus: document.querySelector("#metric-status"),
        metricService: document.querySelector("#metric-service"),
        metricRequests: document.querySelector("#metric-requests"),
        metricSuccess: document.querySelector("#metric-success"),
        metricTools: document.querySelector("#metric-tools"),
        metricToolDetail: document.querySelector("#metric-tool-detail"),
        metricSkills: document.querySelector("#metric-skills"),
        metricSkillErrors: document.querySelector("#metric-skill-errors"),
        askForm: document.querySelector("#ask-form"),
        askQuestion: document.querySelector("#ask-question"),
        askSessionId: document.querySelector("#ask-session-id"),
        askOutput: document.querySelector("#ask-output"),
        approvalForm: document.querySelector("#approval-form"),
        approvalExecutionId: document.querySelector("#approval-execution-id"),
        approvalDecision: document.querySelector("#approval-decision"),
        approvalDecidedBy: document.querySelector("#approval-decided-by"),
        approvalComment: document.querySelector("#approval-comment"),
        lookupApproval: document.querySelector("#lookup-approval"),
        approvalOutput: document.querySelector("#approval-output"),
        explorerForm: document.querySelector("#explorer-form"),
        explorerMethod: document.querySelector("#explorer-method"),
        explorerPath: document.querySelector("#explorer-path"),
        explorerBody: document.querySelector("#explorer-body"),
        explorerMeta: document.querySelector("#explorer-response-meta"),
        explorerOutput: document.querySelector("#explorer-output"),
        history: document.querySelector("#request-history"),
        clearHistory: document.querySelector("#clear-history"),
        toast: document.querySelector("#toast"),
    };

    const requestHistory = [];
    let toastTimer;

    function defaultBaseUrl() {
        if (window.location.protocol === "http:" || window.location.protocol === "https:") {
            return window.location.origin;
        }
        return "http://localhost:8080";
    }

    function normalizeBaseUrl(value) {
        return (value || defaultBaseUrl()).trim().replace(/\/+$/, "");
    }

    function normalizePath(path) {
        const value = (path || "").trim();
        if (!value) {
            throw new Error("请求路径不能为空");
        }
        return value.startsWith("/") ? value : `/${value}`;
    }

    function currentConfig() {
        return {
            baseUrl: normalizeBaseUrl(elements.baseUrl.value),
            token: elements.token.value.trim(),
        };
    }

    function formatJson(value) {
        if (typeof value === "string") {
            return value;
        }
        return JSON.stringify(value, null, 2);
    }

    function setOutput(target, value) {
        target.textContent = formatJson(value);
    }

    function showToast(message) {
        window.clearTimeout(toastTimer);
        elements.toast.textContent = message;
        elements.toast.classList.add("visible");
        toastTimer = window.setTimeout(() => elements.toast.classList.remove("visible"), 2200);
    }

    function setConnectionState(state, label) {
        elements.connectionState.dataset.state = state;
        elements.connectionLabel.textContent = label;
    }

    function setBusy(button, busy, busyLabel = "请求中…") {
        if (!button) {
            return;
        }
        if (busy) {
            button.dataset.originalLabel = button.textContent;
            button.textContent = busyLabel;
            button.disabled = true;
        } else {
            button.textContent = button.dataset.originalLabel || button.textContent;
            button.disabled = false;
        }
    }

    function addHistory(entry) {
        requestHistory.unshift(entry);
        requestHistory.splice(8);
        renderHistory();
    }

    function renderHistory() {
        elements.history.replaceChildren();
        if (requestHistory.length === 0) {
            const empty = document.createElement("p");
            empty.className = "empty-state";
            empty.textContent = "当前页面还没有请求记录。";
            elements.history.append(empty);
            return;
        }

        requestHistory.forEach((entry) => {
            const item = document.createElement("div");
            item.className = "history-item";

            const method = document.createElement("span");
            method.className = "history-method";
            method.textContent = entry.method;

            const path = document.createElement("span");
            path.className = "history-path";
            path.title = entry.path;
            path.textContent = entry.path;

            const status = document.createElement("span");
            status.className = `history-status ${entry.ok ? "success" : "error"}`;
            status.textContent = entry.status;

            const duration = document.createElement("span");
            duration.className = "history-duration";
            duration.textContent = `${entry.durationMs}ms`;

            item.append(method, path, status, duration);
            elements.history.append(item);
        });
    }

    async function parseResponse(response) {
        const text = await response.text();
        if (!text) {
            return null;
        }
        const contentType = response.headers.get("content-type") || "";
        if (contentType.includes("application/json")) {
            try {
                return JSON.parse(text);
            } catch {
                return {raw: text, parseError: "响应声明为 JSON，但解析失败"};
            }
        }
        try {
            return JSON.parse(text);
        } catch {
            return text;
        }
    }

    async function apiRequest(path, options = {}) {
        const method = (options.method || "GET").toUpperCase();
        const normalizedPath = normalizePath(path);
        const {baseUrl, token} = currentConfig();
        const headers = new Headers({Accept: "application/json"});
        if (token) {
            headers.set("Authorization", `Bearer ${token}`);
        }
        const request = {method, headers};
        if (options.body !== undefined && options.body !== null && method !== "GET") {
            headers.set("Content-Type", "application/json");
            request.body = JSON.stringify(options.body);
        }

        const startedAt = performance.now();
        try {
            const response = await fetch(`${baseUrl}${normalizedPath}`, request);
            const data = await parseResponse(response);
            const durationMs = Math.round(performance.now() - startedAt);
            addHistory({
                method,
                path: normalizedPath,
                status: `HTTP ${response.status}`,
                ok: response.ok,
                durationMs,
            });
            return {
                ok: response.ok,
                status: response.status,
                statusText: response.statusText,
                durationMs,
                data,
            };
        } catch (error) {
            const durationMs = Math.round(performance.now() - startedAt);
            addHistory({
                method,
                path: normalizedPath,
                status: "NETWORK",
                ok: false,
                durationMs,
            });
            throw new Error(`无法连接 ${baseUrl}${normalizedPath}：${error.message}`);
        }
    }

    function responseEnvelope(result) {
        return {
            http: {
                status: result.status,
                statusText: result.statusText,
                durationMs: result.durationMs,
            },
            data: result.data,
        };
    }

    function percent(value) {
        if (!Number.isFinite(value)) {
            return "—";
        }
        return `${(value * 100).toFixed(1)}%`;
    }

    async function refreshOverview() {
        setBusy(elements.refreshOverview, true, "刷新中…");
        const calls = await Promise.allSettled([
            apiRequest("/api/status"),
            apiRequest("/api/stats"),
            apiRequest("/api/tools"),
            apiRequest("/api/skills"),
        ]);

        const [statusCall, statsCall, toolsCall, skillsCall] = calls;
        if (statusCall.status === "fulfilled" && statusCall.value.ok) {
            const status = statusCall.value.data || {};
            elements.metricStatus.textContent = status.status || "UP";
            elements.metricService.textContent = status.service || "KubeOnCall";
            setConnectionState("online", `已连接 · ${statusCall.value.durationMs}ms`);
        } else {
            elements.metricStatus.textContent = "DOWN";
            elements.metricService.textContent = "连接失败";
            setConnectionState("error", "连接失败");
        }

        if (statsCall.status === "fulfilled" && statsCall.value.ok) {
            const stats = statsCall.value.data || {};
            elements.metricRequests.textContent = stats.totalRequests ?? "0";
            const successRate = stats.totalRequests
                ? Number(stats.successCount || 0) / Number(stats.totalRequests)
                : 0;
            elements.metricSuccess.textContent = `成功 ${percent(successRate)}`;
        } else {
            elements.metricRequests.textContent = "—";
            elements.metricSuccess.textContent = "需要 Viewer Token";
        }

        if (toolsCall.status === "fulfilled" && toolsCall.value.ok) {
            const tools = toolsCall.value.data || {};
            const planner = Array.isArray(tools.planner) ? tools.planner.length : 0;
            const executor = Array.isArray(tools.executor) ? tools.executor.length : 0;
            const verifier = Array.isArray(tools.verifier) ? tools.verifier.length : 0;
            elements.metricTools.textContent = planner + executor + verifier;
            elements.metricToolDetail.textContent = `P ${planner} · E ${executor} · V ${verifier}`;
        } else {
            elements.metricTools.textContent = "—";
            elements.metricToolDetail.textContent = "目录不可用";
        }

        if (skillsCall.status === "fulfilled" && skillsCall.value.ok) {
            const payload = skillsCall.value.data || {};
            const skills = Array.isArray(payload.skills) ? payload.skills : [];
            const errors = Array.isArray(payload.loadErrors) ? payload.loadErrors : [];
            elements.metricSkills.textContent = skills.length;
            elements.metricSkillErrors.textContent = errors.length ? `${errors.length} 个加载错误` : "无加载错误";
        } else {
            elements.metricSkills.textContent = "—";
            elements.metricSkillErrors.textContent = "目录不可用";
        }

        setBusy(elements.refreshOverview, false);
    }

    async function executeAsk(event) {
        event.preventDefault();
        const button = elements.askForm.querySelector('button[type="submit"]');
        setBusy(button, true, "执行中…");
        setOutput(elements.askOutput, "正在等待工作流响应…");
        const payload = {
            question: elements.askQuestion.value.trim(),
        };
        const sessionId = elements.askSessionId.value.trim();
        if (sessionId) {
            payload.sessionId = sessionId;
        }

        try {
            const result = await apiRequest("/api/ask", {method: "POST", body: payload});
            setOutput(elements.askOutput, responseEnvelope(result));
            if (result.data && result.data.executionId) {
                elements.approvalExecutionId.value = result.data.executionId;
            }
            if (result.data && result.data.sessionId) {
                elements.askSessionId.value = result.data.sessionId;
            }
        } catch (error) {
            setOutput(elements.askOutput, {error: error.message});
        } finally {
            setBusy(button, false);
        }
    }

    async function lookupApproval() {
        const executionId = elements.approvalExecutionId.value.trim();
        if (!executionId) {
            showToast("请先填写 Execution ID");
            return;
        }
        setBusy(elements.lookupApproval, true, "查询中…");
        try {
            const result = await apiRequest(`/api/approvals/${encodeURIComponent(executionId)}`);
            setOutput(elements.approvalOutput, responseEnvelope(result));
        } catch (error) {
            setOutput(elements.approvalOutput, {error: error.message});
        } finally {
            setBusy(elements.lookupApproval, false);
        }
    }

    async function submitApproval(event) {
        event.preventDefault();
        const executionId = elements.approvalExecutionId.value.trim();
        const button = elements.approvalForm.querySelector('button[type="submit"]');
        setBusy(button, true, "提交中…");
        try {
            const result = await apiRequest(`/api/approvals/${encodeURIComponent(executionId)}`, {
                method: "POST",
                body: {
                    decision: elements.approvalDecision.value,
                    comment: elements.approvalComment.value.trim() || null,
                    decidedBy: elements.approvalDecidedBy.value.trim() || "console-user",
                },
            });
            setOutput(elements.approvalOutput, responseEnvelope(result));
        } catch (error) {
            setOutput(elements.approvalOutput, {error: error.message});
        } finally {
            setBusy(button, false);
        }
    }

    function parseExplorerBody(method) {
        const raw = elements.explorerBody.value.trim();
        if (!raw || method === "GET") {
            return null;
        }
        try {
            return JSON.parse(raw);
        } catch (error) {
            throw new Error(`JSON Body 格式错误：${error.message}`);
        }
    }

    async function executeExplorer(event) {
        event.preventDefault();
        const button = elements.explorerForm.querySelector('button[type="submit"]');
        const method = elements.explorerMethod.value;
        setBusy(button, true, "发送中…");
        try {
            const body = parseExplorerBody(method);
            const result = await apiRequest(elements.explorerPath.value, {method, body});
            elements.explorerMeta.textContent =
                `HTTP ${result.status} · ${result.durationMs}ms`;
            setOutput(elements.explorerOutput, responseEnvelope(result));
        } catch (error) {
            elements.explorerMeta.textContent = "Request failed";
            setOutput(elements.explorerOutput, {error: error.message});
        } finally {
            setBusy(button, false);
        }
    }

    async function copyOutput(button) {
        const target = document.getElementById(button.dataset.copyTarget);
        if (!target) {
            return;
        }
        try {
            await navigator.clipboard.writeText(target.textContent);
            showToast("已复制到剪贴板");
        } catch {
            showToast("复制失败，请手动选择内容");
        }
    }

    elements.baseUrl.value = defaultBaseUrl();
    elements.connectionForm.addEventListener("submit", (event) => {
        event.preventDefault();
        refreshOverview();
    });
    elements.refreshOverview.addEventListener("click", refreshOverview);
    elements.toggleToken.addEventListener("click", () => {
        const visible = elements.token.type === "text";
        elements.token.type = visible ? "password" : "text";
        elements.toggleToken.textContent = visible ? "显示" : "隐藏";
    });
    elements.askForm.addEventListener("submit", executeAsk);
    elements.lookupApproval.addEventListener("click", lookupApproval);
    elements.approvalForm.addEventListener("submit", submitApproval);
    elements.explorerForm.addEventListener("submit", executeExplorer);
    elements.explorerMethod.addEventListener("change", () => {
        elements.explorerBody.disabled = elements.explorerMethod.value === "GET";
    });
    elements.explorerBody.disabled = elements.explorerMethod.value === "GET";
    elements.clearHistory.addEventListener("click", () => {
        requestHistory.length = 0;
        renderHistory();
    });
    document.querySelectorAll("[data-copy-target]").forEach((button) => {
        button.addEventListener("click", () => copyOutput(button));
    });
})();
