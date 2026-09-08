/**
 * FNMF CLOUD ADMIN - SECURE CLIENT ENGINE
 * - In-memory token storage strictly (RAM only).
 * - Strict DOM createElement & textContent for XSS prevention.
 * - Strict CSP compliant (No inline handlers).
 */
(function() {
    'use strict';

    // In-memory token state
    let authToken = null;
    let currentAdminEmail = null;
    let autoRefreshTimer = null;
    let isFetchingOverview = false;
    let isActionPending = false;
    let cachedDbData = null;
    let currentTab = 'wallets';

    // DOM Elements
    const authCard = document.getElementById('authCard');
    const authLoggedOut = document.getElementById('authLoggedOut');
    const authLoggedIn = document.getElementById('authLoggedIn');
    const adminEmailInput = document.getElementById('adminEmailInput');
    const adminPasswordInput = document.getElementById('adminPasswordInput');
    const btnLogin = document.getElementById('btnLogin');
    const btnLogout = document.getElementById('btnLogout');
    const authEmailDisplay = document.getElementById('authEmailDisplay');
    const lastSyncTime = document.getElementById('lastSyncTime');
    const notificationBanner = document.getElementById('notificationBanner');
    const autoRefreshToggle = document.getElementById('autoRefreshToggle');
    const btnManualRefresh = document.getElementById('btnManualRefresh');
    const tableContainer = document.getElementById('tableContainer');
    const consoleLogs = document.getElementById('consoleLogs');

    const topupTarget = document.getElementById('topupTarget');
    const topupAmount = document.getElementById('topupAmount');
    const btnDoTopup = document.getElementById('btnDoTopup');

    const cryptoTarget = document.getElementById('cryptoTarget');
    const cryptoSymbol = document.getElementById('cryptoSymbol');
    const cryptoQty = document.getElementById('cryptoQty');
    const cryptoPrice = document.getElementById('cryptoPrice');
    const btnDoGrant = document.getElementById('btnDoGrant');

    const manageTarget = document.getElementById('manageTarget');
    const customBalance = document.getElementById('customBalance');
    const btnDoSetBalance = document.getElementById('btnDoSetBalance');
    const btnDoReset = document.getElementById('btnDoReset');

    const tabButtons = document.querySelectorAll('.tab-btn');
    const presetButtons = document.querySelectorAll('.btn-preset');

    // Logging function using textContent strictly
    function logMessage(msg, type) {
        if (!consoleLogs) return;
        const entry = document.createElement('div');
        entry.className = 'console-entry ' + (type || 'info');
        const now = new Date().toLocaleTimeString();
        entry.textContent = '[' + now + '] ' + msg;
        consoleLogs.appendChild(entry);
        consoleLogs.scrollTop = consoleLogs.scrollHeight;
    }

    function showBanner(message, type) {
        if (!notificationBanner) return;
        notificationBanner.textContent = message;
        notificationBanner.className = 'notification-banner ' + (type === 'error' ? 'error' : 'success');
        notificationBanner.classList.remove('hidden');
        setTimeout(() => {
            if (notificationBanner) notificationBanner.classList.add('hidden');
        }, 5000);
    }

    // Authenticated fetch wrapper
    async function apiFetch(url, options) {
        options = options || {};
        options.headers = options.headers || {};
        if (authToken) {
            options.headers['Authorization'] = 'Bearer ' + authToken;
        }

        const response = await fetch(url, options);

        if (response.status === 401 || response.status === 403) {
            const statusText = response.status === 401 ? '401 Unauthorized' : '403 Forbidden';
            logMessage('⚠️ Lỗi ' + statusText + ' tại ' + url + ': Yêu cầu bị từ chối.', 'err');
            if (response.status === 403) {
                showBanner('Truy cập bị từ chối: Tài khoản không có quyền ADMIN.', 'error');
            }
            logout();
            throw new Error('Yêu cầu bị từ chối (' + statusText + ')');
        }

        return response;
    }

    // Login handler
    async function login() {
        const identifier = adminEmailInput.value.trim();
        const password = adminPasswordInput.value.trim();

        if (!identifier || !password) {
            showBanner('Vui lòng nhập đầy đủ tên đăng nhập/email và mật khẩu!', 'error');
            return;
        }

        btnLogin.disabled = true;
        logMessage('Đang xác thực đăng nhập: ' + identifier + '...', 'info');

        try {
            const res = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: identifier, password: password })
            });

            if (!res.ok) {
                const errData = await res.json().catch(() => ({}));
                throw new Error(errData.message || ('Đăng nhập thất bại (HTTP ' + res.status + ')'));
            }

            const data = await res.json();
            if (!data.token) {
                throw new Error('Không nhận được token từ server');
            }

            // Lưu token chỉ trong bộ nhớ RAM
            authToken = data.token;
            currentAdminEmail = (data.user && data.user.email) ? data.user.email : identifier;

            // Xóa mật khẩu khỏi input form ngay sau khi đăng nhập
            adminPasswordInput.value = '';

            // Xác minh quyền ADMIN bằng cách gọi endpoint admin overview
            logMessage('Đang kiểm tra quyền ADMIN trên hệ thống...', 'info');
            await loadDbOverview(true);

            // Thành công -> chuyển giao diện sang trạng thái Logged In
            authLoggedOut.classList.add('hidden');
            authLoggedIn.classList.remove('hidden');
            authEmailDisplay.textContent = currentAdminEmail;

            showBanner('Đăng nhập ADMIN thành công!', 'success');
            logMessage('✅ Xác thực ADMIN hoàn tất. Sẵn sàng quản trị CSDL Cloud.', 'info');

            startAutoRefresh();
        } catch (e) {
            authToken = null;
            currentAdminEmail = null;
            logMessage('❌ ' + e.message, 'err');
            showBanner(e.message, 'error');
        } finally {
            btnLogin.disabled = false;
        }
    }

    // Logout handler
    function logout() {
        authToken = null;
        currentAdminEmail = null;
        cachedDbData = null;
        stopAutoRefresh();

        if (authLoggedOut) authLoggedOut.classList.remove('hidden');
        if (authLoggedIn) authLoggedIn.classList.add('hidden');
        if (authEmailDisplay) authEmailDisplay.textContent = '';
        if (adminPasswordInput) adminPasswordInput.value = '';

        renderEmptyState('Vui lòng đăng nhập quyền ADMIN để xem dữ liệu.');
        logMessage('Đã đăng xuất phiên làm việc. Dữ liệu token trong bộ nhớ đã được giải phóng.', 'warn');
    }

    // Fetch live database overview
    async function loadDbOverview(isManual) {
        if (!authToken) return;
        if (isFetchingOverview) return;

        isFetchingOverview = true;
        if (btnManualRefresh) btnManualRefresh.disabled = true;

        try {
            const res = await apiFetch('/api/admin/db/overview', { method: 'GET' });
            if (!res.ok) {
                throw new Error('Lỗi nạp dữ liệu (HTTP ' + res.status + ')');
            }

            const data = await res.json();
            cachedDbData = data;
            renderCurrentTable();

            if (lastSyncTime) {
                lastSyncTime.textContent = new Date().toLocaleTimeString();
            }

            if (isManual) {
                logMessage('Đã đồng bộ dữ liệu CSDL PostgreSQL Cloud.', 'info');
            }
        } catch (e) {
            logMessage('Không thể nạp dữ liệu overview: ' + e.message, 'err');
        } finally {
            isFetchingOverview = false;
            if (btnManualRefresh) btnManualRefresh.disabled = false;
        }
    }

    function renderEmptyState(text) {
        while (tableContainer.firstChild) {
            tableContainer.removeChild(tableContainer.firstChild);
        }
        const emptyDiv = document.createElement('div');
        emptyDiv.className = 'empty-state';
        emptyDiv.textContent = text;
        tableContainer.appendChild(emptyDiv);
    }

    function renderCurrentTable() {
        if (!cachedDbData) {
            renderEmptyState('Chưa có dữ liệu hoặc phiên đăng nhập đã hết hạn.');
            return;
        }

        const list = cachedDbData[currentTab] || [];
        while (tableContainer.firstChild) {
            tableContainer.removeChild(tableContainer.firstChild);
        }

        if (list.length === 0) {
            renderEmptyState('Bảng hiện tại không có bản ghi nào.');
            return;
        }

        const table = document.createElement('table');
        const thead = document.createElement('thead');
        const tbody = document.createElement('tbody');
        const headerRow = document.createElement('tr');

        let columns = [];
        if (currentTab === 'wallets') {
            columns = [
                { key: 'ID', label: 'ID' },
                { key: 'USER_ID', label: 'User ID' },
                { key: 'BALANCE_USD', label: 'Số Dư ($ USD)' },
                { key: 'INITIAL_BALANCE', label: 'Số Dư Ban Đầu' },
                { key: 'UPDATED_AT', label: 'Cập Nhật' }
            ];
        } else if (currentTab === 'holdings') {
            columns = [
                { key: 'ID', label: 'ID' },
                { key: 'WALLET_ID', label: 'Ví ID' },
                { key: 'SYMBOL', label: 'Mã Coin' },
                { key: 'QUANTITY', label: 'Số Lượng' },
                { key: 'AVG_BUY_PRICE', label: 'Giá Vốn ($)' },
                { key: 'UPDATED_AT', label: 'Cập Nhật' }
            ];
        } else if (currentTab === 'users') {
            columns = [
                { key: 'ID', label: 'ID' },
                { key: 'EMAIL', label: 'Tên Đăng Nhập / Email' },
                { key: 'FULL_NAME', label: 'Họ Và Tên' },
                { key: 'ROLE', label: 'Phân Quyền' },
                { key: 'CREATED_AT', label: 'Ngày Tạo' }
            ];
        } else if (currentTab === 'transactions') {
            columns = [
                { key: 'ID', label: 'ID' },
                { key: 'WALLET_ID', label: 'Ví ID' },
                { key: 'SYMBOL', label: 'Mã Coin' },
                { key: 'TYPE', label: 'Loại Lệnh' },
                { key: 'PRICE', label: 'Giá ($)' },
                { key: 'QUANTITY', label: 'Số Lượng' },
                { key: 'TOTAL_AMOUNT', label: 'Tổng Giá Trị ($)' },
                { key: 'CREATED_AT', label: 'Thời Gian' }
            ];
        }

        columns.forEach(col => {
            const th = document.createElement('th');
            th.textContent = col.label;
            headerRow.appendChild(th);
        });
        thead.appendChild(headerRow);
        table.appendChild(thead);

        list.forEach(row => {
            const tr = document.createElement('tr');
            columns.forEach(col => {
                const td = document.createElement('td');
                // Support both uppercase and lowercase DB column keys
                const val = (row[col.key] !== undefined) ? row[col.key] :
                            (row[col.key.toLowerCase()] !== undefined ? row[col.key.toLowerCase()] : '');
                td.textContent = (val !== null && val !== undefined) ? String(val) : '';
                tr.appendChild(td);
            });
            tbody.appendChild(tr);
        });

        table.appendChild(tbody);
        tableContainer.appendChild(table);
    }

    // Auto Refresh management
    function startAutoRefresh() {
        stopAutoRefresh();
        if (autoRefreshToggle && !autoRefreshToggle.checked) return;

        autoRefreshTimer = setInterval(() => {
            if (document.visibilityState === 'hidden') return;
            if (authToken) {
                loadDbOverview(false);
            }
        }, 5000);
    }

    function stopAutoRefresh() {
        if (autoRefreshTimer) {
            clearInterval(autoRefreshTimer);
            autoRefreshTimer = null;
        }
    }

    // Operations
    async function executeOperation(btn, actionName, confirmText, endpoint, payload) {
        if (!authToken) {
            showBanner('Vui lòng đăng nhập ADMIN trước khi thao tác!', 'error');
            return;
        }
        if (isActionPending) return;

        if (!window.confirm(confirmText)) return;

        isActionPending = true;
        btn.disabled = true;
        logMessage('Bắt đầu thao tác: ' + actionName + '...', 'info');

        try {
            const res = await apiFetch(endpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            const data = await res.json().catch(() => ({}));
            if (!res.ok || data.status === 'ERROR') {
                throw new Error(data.message || ('Thao tác thất bại (HTTP ' + res.status + ')'));
            }

            const successMsg = data.message || (actionName + ' thành công!');
            logMessage('✅ ' + successMsg, 'info');
            showBanner(successMsg, 'success');

            // Refresh overview data immediately after mutation
            await loadDbOverview(true);
        } catch (e) {
            logMessage('❌ ' + e.message, 'err');
            showBanner(e.message, 'error');
        } finally {
            isActionPending = false;
            btn.disabled = false;
        }
    }

    // Event Listeners setup
    function setupEventListeners() {
        btnLogin.addEventListener('click', login);
        adminPasswordInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') login();
        });
        adminEmailInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') login();
        });

        btnLogout.addEventListener('click', logout);

        btnManualRefresh.addEventListener('click', () => loadDbOverview(true));

        autoRefreshToggle.addEventListener('change', (e) => {
            if (e.target.checked) {
                startAutoRefresh();
            } else {
                stopAutoRefresh();
            }
        });

        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible' && authToken && autoRefreshToggle.checked) {
                loadDbOverview(false);
            }
        });

        // Tab buttons
        tabButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                tabButtons.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                currentTab = btn.getAttribute('data-tab');
                renderCurrentTable();
            });
        });

        // Preset amount buttons
        presetButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                const targetId = btn.getAttribute('data-target');
                const val = btn.getAttribute('data-value');
                const targetInput = document.getElementById(targetId);
                if (targetInput) targetInput.value = val;
            });
        });

        // USD Topup
        btnDoTopup.addEventListener('click', () => {
            const target = topupTarget.value.trim();
            const amount = topupAmount.value.trim();
            if (!target) {
                showBanner('Vui lòng nhập tên đăng nhập hoặc email tài khoản cần nạp tiền!', 'error');
                return;
            }
            if (!amount || parseFloat(amount) <= 0) {
                showBanner('Vui lòng nhập số tiền nạp hợp lệ (> 0)!', 'error');
                return;
            }

            executeOperation(
                btnDoTopup,
                'Nạp tiền USD',
                'Xác nhận nạp $' + amount + ' USD vào tài khoản "' + target + '"?',
                '/api/admin/topup',
                { identifier: target, amount: parseFloat(amount) }
            );
        });

        // Grant Crypto
        btnDoGrant.addEventListener('click', () => {
            const target = cryptoTarget.value.trim();
            const symbol = cryptoSymbol.value.trim();
            const quantity = cryptoQty.value.trim();
            const price = cryptoPrice.value.trim();

            if (!target) {
                showBanner('Vui lòng nhập tên đăng nhập hoặc email tài khoản cần cấp tài sản!', 'error');
                return;
            }
            if (!quantity || parseFloat(quantity) <= 0) {
                showBanner('Số lượng tài sản không hợp lệ!', 'error');
                return;
            }

            executeOperation(
                btnDoGrant,
                'Cấp Coin',
                'Xác nhận cấp ' + quantity + ' ' + symbol + ' cho tài khoản "' + target + '"?',
                '/api/admin/grant-crypto',
                { identifier: target, symbol: symbol, quantity: parseFloat(quantity), avgBuyPrice: parseFloat(price) }
            );
        });

        // Set Balance
        btnDoSetBalance.addEventListener('click', () => {
            const target = manageTarget.value.trim();
            const balance = customBalance.value.trim();

            if (!target) {
                showBanner('Vui lòng nhập tên đăng nhập hoặc email tài khoản!', 'error');
                return;
            }
            if (!balance || parseFloat(balance) < 0) {
                showBanner('Số dư đặt mới không hợp lệ (>= 0)!', 'error');
                return;
            }

            executeOperation(
                btnDoSetBalance,
                'Đặt số dư',
                'Xác nhận đặt số dư của tài khoản "' + target + '" thành $' + balance + ' USD?',
                '/api/admin/set-balance',
                { identifier: target, balance: parseFloat(balance) }
            );
        });

        // Reset Account
        btnDoReset.addEventListener('click', () => {
            const target = manageTarget.value.trim();
            if (!target) {
                showBanner('Vui lòng nhập tên đăng nhập hoặc email tài khoản cần reset!', 'error');
                return;
            }

            executeOperation(
                btnDoReset,
                'Reset tài khoản',
                'CẢNH BÁO: Bạn có chắc chắn muốn RESET toàn bộ danh mục và đặt lại số dư về $10,000 USD cho "' + target + '"?',
                '/api/admin/reset',
                { identifier: target }
            );
        });
    }

    // Initialize on DOM ready
    document.addEventListener('DOMContentLoaded', () => {
        setupEventListeners();
        renderEmptyState('Vui lòng đăng nhập quyền ADMIN để xem dữ liệu.');
    });
})();
