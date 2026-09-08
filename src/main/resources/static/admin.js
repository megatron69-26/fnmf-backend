/**
 * FNMF CLOUD ADMIN - SECURE CLIENT ENGINE
 * - In-memory token storage strictly (RAM only).
 * - Strict DOM createElement & textContent for XSS prevention.
 * - Strict CSP compliant (No inline handlers, no inline styles).
 * - Manages USERS and WALLETS with direct balance setting and legacy email updating.
 */
(function() {
    'use strict';

    // In-memory token and state
    let authToken = null;
    let currentAdminEmail = null;
    let autoRefreshTimer = null;
    let isFetchingOverview = false;
    let isActionPending = false;
    let cachedDbData = null;
    let currentTab = 'users';
    let targetUserIdForEmailUpdate = null;
    let targetUserForBalanceUpdate = null;

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

    // Email Modal Elements
    const emailUpdateModal = document.getElementById('emailUpdateModal');
    const modalOldIdentifier = document.getElementById('modalOldIdentifier');
    const modalNewEmailInput = document.getElementById('modalNewEmailInput');
    const btnCancelEmailModal = document.getElementById('btnCancelEmailModal');
    const btnConfirmEmailModal = document.getElementById('btnConfirmEmailModal');

    // Balance Modal Elements
    const balanceUpdateModal = document.getElementById('balanceUpdateModal');
    const modalBalanceUserId = document.getElementById('modalBalanceUserId');
    const modalBalanceEmail = document.getElementById('modalBalanceEmail');
    const modalBalanceCurrent = document.getElementById('modalBalanceCurrent');
    const modalNewBalanceInput = document.getElementById('modalNewBalanceInput');
    const btnCancelBalanceModal = document.getElementById('btnCancelBalanceModal');
    const btnConfirmBalanceModal = document.getElementById('btnConfirmBalanceModal');

    const tabButtons = document.querySelectorAll('.tab-btn');

    const EMAIL_REGEX = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

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
        const email = adminEmailInput.value.trim().toLowerCase();
        const password = adminPasswordInput.value;

        if (!email || !password) {
            showBanner('Vui lòng nhập đầy đủ Email quản trị và Mật khẩu!', 'error');
            return;
        }

        if (!EMAIL_REGEX.test(email)) {
            showBanner('Định dạng Email quản trị không hợp lệ!', 'error');
            return;
        }

        btnLogin.disabled = true;
        logMessage('Đang xác thực đăng nhập: ' + email + '...', 'info');

        try {
            const res = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: email, password: password })
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
            currentAdminEmail = (data.user && data.user.email) ? data.user.email : email;

            // Xóa mật khẩu khỏi input form ngay sau khi đăng nhập
            adminPasswordInput.value = '';

            // Xác minh quyền ADMIN và tải dữ liệu tổng quan
            logMessage('Đang kiểm tra quyền ADMIN và tải dữ liệu...', 'info');
            try {
                await loadDbOverview(true);
            } catch (overviewErr) {
                // Đăng nhập auth thành công nhưng overview thất bại
                stopAutoRefresh();
                authToken = null;
                currentAdminEmail = null;
                cachedDbData = null;
                renderEmptyState('Không thể tải dữ liệu quản trị từ máy chủ.');
                const errMsg = 'Đăng nhập thành công nhưng không tải được dữ liệu quản trị';
                logMessage('❌ ' + errMsg + ': ' + overviewErr.message, 'err');
                showBanner(errMsg, 'error');
                return;
            }

            // Chỉ chuyển giao diện sang trạng thái Logged In khi overview thành công
            authLoggedOut.classList.add('hidden');
            authLoggedIn.classList.remove('hidden');
            authEmailDisplay.textContent = currentAdminEmail;

            showBanner('Đăng nhập ADMIN thành công!', 'success');
            logMessage('✅ Xác thực ADMIN hoàn tất. Sẵn sàng quản trị CSDL Cloud.', 'info');

            startAutoRefresh();
        } catch (e) {
            authToken = null;
            currentAdminEmail = null;
            stopAutoRefresh();
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
                const errData = await res.json().catch(() => ({}));
                throw new Error(errData.message || ('Lỗi nạp dữ liệu (HTTP ' + res.status + ')'));
            }

            const data = await res.json();
            if (data.status === 'ERROR') {
                throw new Error(data.message || 'Lỗi nạp dữ liệu quản trị');
            }
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
            throw e;
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

    // Modal Email handlers
    function openEmailModal(userId, oldEmail) {
        targetUserIdForEmailUpdate = userId;
        modalOldIdentifier.textContent = oldEmail;
        modalNewEmailInput.value = '';
        emailUpdateModal.classList.remove('hidden');
        modalNewEmailInput.focus();
    }

    function closeEmailModal() {
        targetUserIdForEmailUpdate = null;
        emailUpdateModal.classList.add('hidden');
    }

    // Modal Balance handlers
    function openBalanceModal(userId, emailStr) {
        targetUserForBalanceUpdate = { userId: userId, email: emailStr };
        modalBalanceUserId.textContent = String(userId);
        modalBalanceEmail.textContent = emailStr;

        let currentBalanceStr = 'Chưa có ví';
        if (cachedDbData && cachedDbData.wallets) {
            const wallet = cachedDbData.wallets.find(w => {
                const uid = (w.user_id !== undefined) ? w.user_id : w.USER_ID;
                return String(uid) === String(userId);
            });
            if (wallet) {
                const bal = (wallet.balance_usd !== undefined) ? wallet.balance_usd : wallet.BALANCE_USD;
                if (bal !== undefined && bal !== null) {
                    const numBal = Number(bal);
                    currentBalanceStr = '$' + numBal.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 4 }) + ' USD';
                }
            }
        }

        modalBalanceCurrent.textContent = currentBalanceStr;
        modalNewBalanceInput.value = '';
        balanceUpdateModal.classList.remove('hidden');
        modalNewBalanceInput.focus();
    }

    function closeBalanceModal() {
        targetUserForBalanceUpdate = null;
        balanceUpdateModal.classList.add('hidden');
    }

    async function confirmBalanceUpdate() {
        if (!targetUserForBalanceUpdate) return;
        if (isActionPending) return;

        const rawVal = modalNewBalanceInput.value.trim();
        if (!rawVal) {
            showBanner('Vui lòng nhập số dư mới!', 'error');
            return;
        }

        const num = Number(rawVal);
        if (isNaN(num) || !isFinite(num) || num < 0) {
            showBanner('Số dư không hợp lệ! Vui lòng nhập số hữu hạn lớn hơn hoặc bằng 0.', 'error');
            return;
        }

        const targetEmail = targetUserForBalanceUpdate.email;
        isActionPending = true;
        btnConfirmBalanceModal.disabled = true;
        logMessage('Đang đặt số dư cho tài khoản ' + targetEmail + ' thành $' + num + ' USD...', 'info');

        try {
            const res = await apiFetch('/api/admin/set-balance', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    email: targetEmail,
                    balance: num
                })
            });

            const data = await res.json().catch(() => ({}));
            if (!res.ok || data.status === 'ERROR') {
                throw new Error(data.message || ('Đặt số dư thất bại (HTTP ' + res.status + ')'));
            }

            const successMsg = data.message || ('Đã đặt số dư tài khoản thành $' + num + ' USD!');
            showBanner(successMsg, 'success');
            logMessage('✅ ' + successMsg, 'info');
            closeBalanceModal();
            await loadDbOverview(true);
        } catch (e) {
            showBanner(e.message, 'error');
            logMessage('❌ Lỗi đặt số dư: ' + e.message, 'err');
        } finally {
            isActionPending = false;
            btnConfirmBalanceModal.disabled = false;
        }
    }

    // Render table based on active tab
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

        if (currentTab === 'users') {
            const userHeaders = ['ID', 'Email Người Dùng', 'Họ Và Tên', 'Phân Quyền', 'Trạng Thái Email', 'Hành Động'];
            userHeaders.forEach(lbl => {
                const th = document.createElement('th');
                th.textContent = lbl;
                headerRow.appendChild(th);
            });
            thead.appendChild(headerRow);
            table.appendChild(thead);

            list.forEach(row => {
                const tr = document.createElement('tr');
                const userId = row.id !== undefined ? row.id : (row.ID !== undefined ? row.ID : '');
                const emailStr = String(row.email !== undefined ? row.email : (row.EMAIL !== undefined ? row.EMAIL : ''));
                const fullNameStr = String(row.full_name !== undefined ? row.full_name : (row.FULL_NAME !== undefined ? row.FULL_NAME : ''));
                const roleStr = String(row.role !== undefined ? row.role : (row.ROLE !== undefined ? row.ROLE : 'USER'));

                const isEmailValid = EMAIL_REGEX.test(emailStr);
                const needsUpdate = (row.needsEmailUpdate !== undefined) ? Boolean(row.needsEmailUpdate) : !isEmailValid;

                // 1. ID
                const tdId = document.createElement('td');
                tdId.textContent = String(userId);
                tr.appendChild(tdId);

                // 2. Email
                const tdEmail = document.createElement('td');
                tdEmail.textContent = emailStr;
                tr.appendChild(tdEmail);

                // 3. Full Name
                const tdName = document.createElement('td');
                tdName.textContent = fullNameStr;
                tr.appendChild(tdName);

                // 4. Role
                const tdRole = document.createElement('td');
                tdRole.textContent = roleStr;
                tr.appendChild(tdRole);

                // 5. Trạng Thái Email
                const tdStatus = document.createElement('td');
                const badge = document.createElement('span');
                if (needsUpdate) {
                    badge.className = 'badge-warn';
                    badge.textContent = '⚠️ Cần cập nhật email';
                } else {
                    badge.className = 'badge-ok';
                    badge.textContent = '✅ Hợp lệ';
                }
                tdStatus.appendChild(badge);
                tr.appendChild(tdStatus);

                // 6. Hành Động
                const tdAction = document.createElement('td');
                if (needsUpdate) {
                    const btnEdit = document.createElement('button');
                    btnEdit.className = 'btn btn-blue btn-xs';
                    btnEdit.textContent = '✉️ Đổi email';
                    btnEdit.addEventListener('click', () => openEmailModal(userId, emailStr));
                    tdAction.appendChild(btnEdit);
                } else {
                    const btnSetBal = document.createElement('button');
                    btnSetBal.className = 'btn btn-blue btn-xs';
                    btnSetBal.textContent = '💎 Đặt số dư';
                    btnSetBal.addEventListener('click', () => openBalanceModal(userId, emailStr));
                    tdAction.appendChild(btnSetBal);
                }
                tr.appendChild(tdAction);

                tbody.appendChild(tr);
            });

            table.appendChild(tbody);
            tableContainer.appendChild(table);
            return;
        }

        // Bảng WALLETS
        let columns = [
            { key: 'ID', label: 'ID' },
            { key: 'USER_ID', label: 'User ID' },
            { key: 'BALANCE_USD', label: 'Số Dư ($ USD)' },
            { key: 'INITIAL_BALANCE', label: 'Số Dư Ban Đầu' },
            { key: 'UPDATED_AT', label: 'Cập Nhật' }
        ];

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
                loadDbOverview(false).catch(() => {});
            }
        }, 5000);
    }

    function stopAutoRefresh() {
        if (autoRefreshTimer) {
            clearInterval(autoRefreshTimer);
            autoRefreshTimer = null;
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

        btnManualRefresh.addEventListener('click', () => {
            loadDbOverview(true).catch(e => {
                showBanner(e.message, 'error');
            });
        });

        autoRefreshToggle.addEventListener('change', (e) => {
            if (e.target.checked) {
                startAutoRefresh();
            } else {
                stopAutoRefresh();
            }
        });

        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible' && authToken && autoRefreshToggle.checked) {
                loadDbOverview(false).catch(() => {});
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

        // Modal Email event listeners
        if (btnCancelEmailModal) {
            btnCancelEmailModal.addEventListener('click', closeEmailModal);
        }
        if (btnConfirmEmailModal) {
            btnConfirmEmailModal.addEventListener('click', async () => {
                if (isActionPending) return;
                const newEmail = modalNewEmailInput.value.trim().toLowerCase();
                if (!newEmail || !EMAIL_REGEX.test(newEmail)) {
                    showBanner('Vui lòng nhập địa chỉ email hợp lệ (VD: user@example.com)!', 'error');
                    return;
                }

                isActionPending = true;
                btnConfirmEmailModal.disabled = true;
                try {
                    const res = await apiFetch('/api/admin/users/' + targetUserIdForEmailUpdate + '/email', {
                        method: 'PATCH',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ email: newEmail })
                    });
                    const data = await res.json().catch(() => ({}));
                    if (!res.ok || data.status === 'ERROR') {
                        throw new Error(data.message || data.error || ('Cập nhật thất bại (HTTP ' + res.status + ')'));
                    }

                    showBanner('Cập nhật email thành công cho user #' + targetUserIdForEmailUpdate + '!', 'success');
                    logMessage('✅ Đã đổi email user #' + targetUserIdForEmailUpdate + ' sang ' + newEmail, 'info');
                    closeEmailModal();
                    await loadDbOverview(true);
                } catch (e) {
                    showBanner(e.message, 'error');
                    logMessage('❌ Lỗi cập nhật email: ' + e.message, 'err');
                } finally {
                    isActionPending = false;
                    btnConfirmEmailModal.disabled = false;
                }
            });
        }

        // Modal Balance event listeners
        if (btnCancelBalanceModal) {
            btnCancelBalanceModal.addEventListener('click', closeBalanceModal);
        }
        if (btnConfirmBalanceModal) {
            btnConfirmBalanceModal.addEventListener('click', confirmBalanceUpdate);
        }
        if (modalNewBalanceInput) {
            modalNewBalanceInput.addEventListener('keypress', (e) => {
                if (e.key === 'Enter') confirmBalanceUpdate();
            });
        }
    }

    // Initialize on DOM ready
    document.addEventListener('DOMContentLoaded', () => {
        setupEventListeners();
        renderEmptyState('Vui lòng đăng nhập quyền ADMIN để xem dữ liệu.');
    });
})();
