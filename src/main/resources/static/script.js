/* ==========================================================
   MITHILA GO - APPLICATION LOGIC SCRIPT
   ========================================================== */

document.addEventListener('DOMContentLoaded', () => {
    let currentRole = null;
    let selectedCabType = null;
    let selectedFare = 0;
    let selectedScheduleDate = "1";
    let ridesHistoryData = [];
    let toastTimeout = null;
    let ridePollTimer = null;
    let driverRefreshTimer = null;
    let currentRide = null;
    let complaintRideId = null;
    let selectedScheduledAt = null;
    let pickupCoordinates = null;
    let destinationCoordinates = null;
    let pickupLabelPending = false;
    let destinationLabelPending = false;
    let currentLocationCoordinates = null;
    let activeLocationInput = null;
    let availableLandmarks = [];
    let remoteLocationSuggestions = [];
    let remoteLocationSearchError = '';
    let locationSearchLoading = false;
    let locationSearchTimer = null;
    let locationSearchRequest = 0;
    let locationLabelRequests = { pickup: 0, dropoff: 0 };
    let currentUser = null;
    let driverLocationTimer = null;
    let driverWatchId = null;
    let csrfRequest = null;
    let availableVehicles = [];
    let wakeLock = null;
    let notificationPollTimer = null;
    let fareQuoteRequest = 0;
    let lastRidePollingErrorAt = 0;
    let driverMap = null;
    let driverMarker = null;
    let driverPickupMarker = null;
    let driverRouteLine = null;
    let driverRouteKey = '';
    let driverRouteContext = '';
    let driverRouteRequestedAt = 0;
    let activeRideMap = null;
    let activeDriverMarker = null;
    let activeDriverAnimationFrame = null;
    let activeDriverMarkerKind = '';
    let activePickupMarker = null;
    let activeDropMarker = null;
    let activeRouteLine = null;
    let activeRouteKey = '';
    let activeRouteContext = '';
    let activeRouteRequestedAt = 0;
    let activeRouteQuote = null;
    let activeRideMapContext = '';
    let currentLanguage = localStorage.getItem('mithila-language') === 'hi' ? 'hi' : 'en';
    const supportedDriverVehicles = ['Bike', 'Auto', 'Mithila E-Rickshaw', 'Mithila Mini', 'Mithila SUV'];

    function supportedDriverVehicle(value) {
        return supportedDriverVehicles.find(vehicle => vehicle.toUpperCase() === String(value || '').trim().toUpperCase()) || null;
    }

    function csrfToken() {
        const cookie = document.cookie.split('; ').find(value => value.startsWith('XSRF-TOKEN='));
        return cookie ? decodeURIComponent(cookie.slice('XSRF-TOKEN='.length)) : '';
    }

    async function api(path, options = {}) {
        if (window.location.protocol === 'file:') {
            throw new Error('Sign-in needs the running app. Start Mithila Go, then open http://localhost:8080 instead of this local file.');
        }
        let response;
        const headers = new Headers(options.headers || {});
        if (!headers.has('Content-Type') && options.body) {
            headers.set('Content-Type', 'application/json');
        }
        if (!['GET', 'HEAD', 'OPTIONS'].includes((options.method || 'GET').toUpperCase())) {
            let token = csrfToken();
            if (!token) {
                if (!csrfRequest) {
                    csrfRequest = fetch('/api/csrf', { credentials: 'same-origin' })
                        .then(async response => {
                            if (!response.ok) throw new Error('Could not start a secure session. Reload and try again.');
                            return response.json();
                        })
                        .finally(() => { csrfRequest = null; });
                }
                const csrf = await csrfRequest;
                token = csrfToken() || csrf.token;
            }
            if (token) headers.set('X-XSRF-TOKEN', token);
        }
        try {
            response = await fetch(`/api${path}`, {
                credentials: 'same-origin',
                ...options,
                headers
            });
        } catch (error) {
            throw new Error('Cannot reach Mithila Go. Open this page through the running Spring Boot app.');
        }
        const data = response.status === 204 ? null : await response.json().catch(() => ({}));
        if (!response.ok) {
            const error = new Error(data?.message || 'Request failed. Please try again.');
            error.status = response.status;
            throw error;
        }
        return data;
    }

    async function pollNotifications() {
        try {
            const items = await api('/notifications');
            items.forEach(item => {
                showToast(item.message);
                if ('Notification' in window && Notification.permission === 'granted' && document.hidden) {
                    new Notification('Mithila Go', { body: item.message, tag: `ride-${item.rideId || item.id}` });
                }
            });
        } catch { /* The ride view remains the source of truth if notifications are unavailable. */ }
    }

    function roleForApi() {
        return currentRole === 'driver' ? 'DRIVER' : 'RIDER';
    }

    function setBusy(button, busy, busyText = 'Please wait...') {
        if (busy) {
            button.dataset.label = button.textContent;
            button.disabled = true;
            button.textContent = busyText;
        } else {
            button.disabled = false;
            button.textContent = button.dataset.label || button.textContent;
            delete button.dataset.label;
        }
    }

    function showToast(message) {
        const toast = document.getElementById('appToast');
        toast.textContent = message;
        toast.classList.add('visible');
        window.clearTimeout(toastTimeout);
        toastTimeout = window.setTimeout(() => toast.classList.remove('visible'), 2800);
    }

    function requestNotificationPermission() {
        if (!('Notification' in window) || Notification.permission !== 'default') return;
        Notification.requestPermission().then(permission => {
            if (permission !== 'granted') {
                showToast('Phone notifications are off; in-app alerts remain available.');
            }
        }).catch(() => {
            showToast('Could not enable phone notifications; in-app alerts remain available.');
        });
    }

    function applyLanguage(language) {
        currentLanguage = language;
        localStorage.setItem('mithila-language', language);
        document.documentElement.lang = language === 'hi' ? 'hi' : 'en';
        document.querySelectorAll('.language-toggle').forEach(button => {
            button.textContent = language === 'hi' ? 'English' : 'हिंदी';
            button.setAttribute('aria-label', language === 'hi' ? 'Switch to English' : 'Switch to Hindi');
            button.setAttribute('aria-pressed', language === 'hi' ? 'true' : 'false');
        });
        document.querySelectorAll('[data-en][data-hi]').forEach(element => {
            element.textContent = language === 'hi' ? element.dataset.hi : element.dataset.en;
        });
        const authTitle = document.getElementById('authTitle');
        if (authTitle && currentRole) {
            authTitle.textContent = language === 'hi'
                ? (currentRole === 'driver' ? 'ड्राइवर पोर्टल में प्रवेश' : 'यात्री पोर्टल में प्रवेश')
                : (currentRole === 'driver' ? 'Driver Partner Portal' : 'Rider Portal Access');
        }
    }

    document.querySelectorAll('.language-toggle').forEach(button => {
        button.addEventListener('click', () => applyLanguage(currentLanguage === 'en' ? 'hi' : 'en'));
    });
    applyLanguage(currentLanguage);
    document.addEventListener('click', event => {
        const toggle = event.target.closest('[data-toggle-password]');
        if (!toggle) return;
        const input = document.getElementById(toggle.dataset.togglePassword);
        if (!input) return;
        const show = input.type === 'password';
        input.type = show ? 'text' : 'password';
        toggle.textContent = show ? 'Hide' : 'Show';
        toggle.setAttribute('aria-label', `${show ? 'Hide' : 'Show'} password`);
    });

    function openSelectedPortal() {
        authView.classList.remove('active');
        document.body.classList.toggle('driver-portal-active', currentRole === 'driver');
        document.querySelector('.assistant-coming-soon').hidden = currentRole !== 'user';
        if (currentRole === 'driver') {
            driverDashboardView.classList.add('active');
        } else {
            userMainApp.classList.add('active');
        }
        if (currentRole === 'driver') {
            if (document.getElementById('driverOnlineToggle').checked) startDriverLocationTracking();
            refreshDriverRides();
            window.clearInterval(driverRefreshTimer);
            driverRefreshTimer = window.setInterval(() => {
                if (driverDashboardView.classList.contains('active')) refreshDriverRides();
            }, 5000);
        } else {
            loadVehicles();
            pollNotifications();
            window.clearInterval(notificationPollTimer);
            notificationPollTimer = window.setInterval(() => {
                if (userMainApp.classList.contains('active')) pollNotifications();
            }, 5000);
        }
    }

    // View Elements
    const roleSelectView = document.getElementById('roleSelectView');
    const authView = document.getElementById('authView');
    const driverDashboardView = document.getElementById('driverDashboardView');
    const userMainApp = document.getElementById('userMainApp');

    // ================= 1. ROLE & AUTHENTICATION WORKFLOW =================
    document.getElementById('selectUserRoleBtn').addEventListener('click', () => {
        currentRole = 'user';
        document.getElementById('authTitle').textContent = currentLanguage === 'hi'
            ? 'यात्री पोर्टल में प्रवेश' : 'Rider Portal Access';
        setupAuthForms();
        roleSelectView.classList.remove('active');
        authView.classList.add('active');
    });

    document.getElementById('selectDriverRoleBtn').addEventListener('click', () => {
        currentRole = 'driver';
        document.getElementById('authTitle').textContent = currentLanguage === 'hi'
            ? 'ड्राइवर पोर्टल में प्रवेश' : 'Driver Partner Portal';
        setupAuthForms();
        roleSelectView.classList.remove('active');
        authView.classList.add('active');
    });

    document.getElementById('backToRoleBtn').addEventListener('click', () => {
        authView.classList.remove('active');
        roleSelectView.classList.add('active');
    });

    // Tab switcher (Sign In vs Create Account)
    const tabLoginBtn = document.getElementById('tabLoginBtn');
    const tabSignupBtn = document.getElementById('tabSignupBtn');
    const loginFormBox = document.getElementById('loginFormBox');
    const signupFormBox = document.getElementById('signupFormBox');

    tabLoginBtn.addEventListener('click', () => {
        tabLoginBtn.classList.add('active');
        tabSignupBtn.classList.remove('active');
        loginFormBox.classList.add('active');
        signupFormBox.classList.remove('active');
    });

    tabSignupBtn.addEventListener('click', () => {
        tabSignupBtn.classList.add('active');
        tabLoginBtn.classList.remove('active');
        signupFormBox.classList.add('active');
        loginFormBox.classList.remove('active');
    });

    // Dynamic Form Builder for Registration
    function setupAuthForms() {
        tabLoginBtn.classList.add('active');
        tabSignupBtn.classList.remove('active');
        loginFormBox.classList.add('active');
        signupFormBox.classList.remove('active');
        const dynamicFields = document.getElementById('dynamicSignupFields');
        const loginId = document.getElementById('loginId');
        const loginIdLabel = document.getElementById('loginIdLabel');
        const loginIdToggle = document.getElementById('loginIdToggle');
        loginId.value = '';
        loginId.removeAttribute('pattern');
        loginId.setCustomValidity('');
        document.getElementById('loginPassword').value = '';
        if (currentRole === 'user') {
            loginId.type = 'text';
            loginId.inputMode = '';
            loginId.maxLength = 150;
            loginId.placeholder = 'Email or phone number';
            loginIdToggle.hidden = true;
            loginIdToggle.textContent = 'Show';
            loginIdLabel.dataset.en = 'PHONE OR EMAIL';
            loginIdLabel.dataset.hi = 'फ़ोन या ईमेल';
            loginIdLabel.textContent = currentLanguage === 'hi' ? loginIdLabel.dataset.hi : loginIdLabel.dataset.en;
            dynamicFields.innerHTML = `
                <div class="input-group"><label class="input-label">FULL NAME</label><input type="text" placeholder="Your full name" autocomplete="name" maxlength="100" required></div>
                <div class="input-group"><label class="input-label">EMAIL ADDRESS</label><input type="email" placeholder="name@example.com" autocomplete="email" maxlength="150" required></div>
                <div class="input-group"><label class="input-label">10-DIGIT PHONE NUMBER</label><input type="tel" inputmode="numeric" placeholder="9876543210" autocomplete="tel" maxlength="15" required></div>
                <div class="input-group"><label class="input-label" for="signupPassword">CREATE PASSWORD</label><input id="signupPassword" type="password" placeholder="At least 8 characters" autocomplete="new-password" minlength="8" maxlength="72" required><button class="password-toggle" type="button" data-toggle-password="signupPassword" aria-label="Show password">Show</button></div>
                <div class="input-group"><label class="input-label" for="signupConfirmPassword">RE-ENTER PASSWORD</label><input id="signupConfirmPassword" type="password" placeholder="Repeat your password" autocomplete="new-password" minlength="8" maxlength="72" required><button class="password-toggle" type="button" data-toggle-password="signupConfirmPassword" aria-label="Show password">Show</button></div>
            `;
        } else {
            loginId.type = 'password';
            loginId.inputMode = 'numeric';
            loginId.pattern = '(?:[0-9]{12}|[^\\s@]+@[^\\s@]+\\.[^\\s@]+|(?:\\+|[0-9]|\\s|\\(|\\)|\\p{Dash}){7,20})';
            loginId.maxLength = 150;
            loginId.placeholder = '12-digit Aadhaar number';
            loginIdToggle.hidden = false;
            loginIdToggle.textContent = 'Show';
            loginIdLabel.dataset.en = 'AADHAAR NUMBER OR REGISTERED EMAIL/PHONE';
            loginIdLabel.dataset.hi = 'आधार नंबर या पंजीकृत ईमेल/फ़ोन';
            loginIdLabel.textContent = currentLanguage === 'hi' ? loginIdLabel.dataset.hi : loginIdLabel.dataset.en;
            dynamicFields.innerHTML = `
                <div class="input-group"><label class="input-label">FULL LEGAL NAME</label><input type="text" placeholder="Your full name" autocomplete="name" maxlength="100" required></div>
                <div class="input-group"><label class="input-label">VEHICLE TYPE</label><select data-driver-vehicle required><option value="">Choose your vehicle</option><option>Bike</option><option>Auto</option><option>Mithila E-Rickshaw</option><option>Mithila Mini</option><option>Mithila SUV</option><option value="OTHER">Other</option></select><select data-other-vehicle aria-label="Choose a supported vehicle" hidden><option value="">Choose an available vehicle</option><option>Bike</option><option>Auto</option><option>Mithila E-Rickshaw</option><option>Mithila Mini</option><option>Mithila SUV</option></select></div>
                <div class="input-group"><label class="input-label">VEHICLE PLATE NUMBER</label><input type="text" placeholder="e.g. BR-07-AB-1234" maxlength="20" required></div>
                <div class="input-group"><label class="input-label">EMAIL ADDRESS</label><input type="email" placeholder="name@example.com" autocomplete="email" maxlength="150" required></div>
                <div class="input-group"><label class="input-label">10-DIGIT PHONE NUMBER</label><input type="tel" inputmode="numeric" placeholder="9876543210" autocomplete="tel" maxlength="15" required></div>
                <div class="input-group"><label class="input-label" for="signupAadhaar">AADHAAR NUMBER</label><input id="signupAadhaar" type="password" inputmode="numeric" pattern="[0-9]{12}" maxlength="12" autocomplete="off" placeholder="Enter your 12-digit Aadhaar number" required><button class="password-toggle" type="button" data-toggle-password="signupAadhaar" aria-label="Show Aadhaar number">Show</button></div>
                <p class="privacy-note">Your full Aadhaar number is never saved. A keyed fingerprint is used for driver sign-in and only the last four digits are retained for display. This app does not perform government identity verification.</p>
                <div class="input-group"><label class="input-label" for="signupPassword">CREATE PASSWORD</label><input id="signupPassword" type="password" placeholder="At least 8 characters" autocomplete="new-password" minlength="8" maxlength="72" required><button class="password-toggle" type="button" data-toggle-password="signupPassword" aria-label="Show password">Show</button></div>
                <div class="input-group"><label class="input-label" for="signupConfirmPassword">RE-ENTER PASSWORD</label><input id="signupConfirmPassword" type="password" placeholder="Repeat your password" autocomplete="new-password" minlength="8" maxlength="72" required><button class="password-toggle" type="button" data-toggle-password="signupConfirmPassword" aria-label="Show password">Show</button></div>
            `;
        }
    }

    document.getElementById('dynamicSignupFields').addEventListener('change', event => {
        if (!event.target.matches('[data-driver-vehicle]')) return;
        const otherVehicle = document.querySelector('[data-other-vehicle]');
        if (!otherVehicle) return;
        otherVehicle.hidden = event.target.value !== 'OTHER';
        otherVehicle.required = event.target.value === 'OTHER';
        otherVehicle.value = '';
    });

    // Login & Registration triggers
    document.getElementById('submitLoginBtn').addEventListener('click', async () => {
        const button = document.getElementById('submitLoginBtn');
        const loginInputs = [document.getElementById('loginId'), document.getElementById('loginPassword')];
        const invalidInput = loginInputs.find(input => !input.reportValidity());
        if (invalidInput) {
            invalidInput.focus();
            return;
        }
        setBusy(button, true, 'Signing in...');
        try {
            const user = await api('/login', { method: 'POST', body: JSON.stringify({
                identifier: loginInputs[0].value.trim(), password: loginInputs[1].value, role: roleForApi()
            }) });
            applyUser(user);
            openSelectedPortal();
            showToast(`Welcome back, ${user.name}.`);
        } catch (error) {
            showToast(error.message);
        } finally {
            setBusy(button, false);
        }
    });

    document.getElementById('submitSignupBtn').addEventListener('click', async () => {
        const button = document.getElementById('submitSignupBtn');
        const signupControls = [...document.querySelectorAll('#dynamicSignupFields input, #dynamicSignupFields select')];
        const driver = currentRole === 'driver';
        const phoneInput = signupControls.find(input => input.type === 'tel');
        phoneInput.setCustomValidity('');
        const invalidInput = signupControls.find(input => !input.reportValidity());
        if (invalidInput) {
            invalidInput.focus();
            return;
        }
        const value = (index) => signupControls[index]?.value.trim() || '';
        const phoneDigits = phoneInput.value.replace(/\D/g, '');
        const normalizedDigits = phoneDigits.startsWith('91') && phoneDigits.length === 12
            ? phoneDigits.slice(2) : phoneDigits;
        if (normalizedDigits.length !== 10) {
            phoneInput.setCustomValidity('Enter a valid 10-digit phone number.');
            phoneInput.reportValidity();
            phoneInput.focus();
            return;
        }
        phoneInput.setCustomValidity('');
        const passwordInput = document.getElementById('signupPassword');
        const confirmPasswordInput = document.getElementById('signupConfirmPassword');
        confirmPasswordInput.setCustomValidity(passwordInput.value === confirmPasswordInput.value
            ? '' : 'Passwords do not match.');
        if (!confirmPasswordInput.reportValidity()) {
            confirmPasswordInput.focus();
            return;
        }
        const selectedVehicleType = document.querySelector('[data-driver-vehicle]')?.value;
        const otherVehicleType = document.querySelector('[data-other-vehicle]')?.value;
        const payload = driver
            ? { name: value(0), vehicleType: selectedVehicleType === 'OTHER' ? otherVehicleType : selectedVehicleType,
                vehiclePlate: value(3), email: value(4), phone: value(5), aadhaarNumber: value(6), password: passwordInput.value,
                confirmPassword: confirmPasswordInput.value, role: roleForApi() }
            : { name: value(0), email: value(1), phone: value(2), password: passwordInput.value,
                confirmPassword: confirmPasswordInput.value, role: roleForApi() };
        setBusy(button, true, 'Creating account...');
        try {
            const user = await api('/signup', { method: 'POST', body: JSON.stringify(payload) });
            tabLoginBtn.click();
            document.getElementById('loginId').value = user.email;
            document.getElementById('loginPassword').value = '';
            const signupAadhaar = document.getElementById('signupAadhaar');
            if (signupAadhaar) signupAadhaar.value = '';
            showToast(currentLanguage === 'hi'
                ? 'खाता बन गया। जारी रखने के लिए साइन इन करें।'
                : 'Account created. Please sign in to continue.');
        } catch (error) {
            showToast(error.message);
        } finally {
            setBusy(button, false);
        }
    });

    function applyUser(user) {
        currentUser = user;
        currentRole = user.role === 'DRIVER' ? 'driver' : 'user';
        const name = document.getElementById('displayUserName');
        const phone = document.getElementById('displayUserPhone');
        if (name) name.textContent = user.name;
        if (phone) phone.textContent = user.phone;
        document.getElementById('profileName').value = user.name;
        document.getElementById('profilePhone').value = user.phone;
        const driverFields = document.getElementById('driverProfileFields');
        driverFields.hidden = user.role !== 'DRIVER';
        if (user.role === 'DRIVER') {
            const vehicleSelect = document.getElementById('profileVehicleType');
            const vehicleStatus = document.getElementById('profileVehicleStatus');
            vehicleSelect.querySelector('[data-unavailable-vehicle]')?.remove();
            const supportedType = supportedDriverVehicle(user.vehicleType);
            if (supportedType) {
                vehicleSelect.value = supportedType;
                vehicleStatus.hidden = true;
            } else {
                const unavailable = document.createElement('option');
                unavailable.value = '__UNAVAILABLE__';
                unavailable.textContent = user.vehicleType
                    ? `${user.vehicleType} (Coming soon)` : 'Vehicle type unavailable (Coming soon)';
                unavailable.disabled = true;
                unavailable.selected = true;
                unavailable.dataset.unavailableVehicle = '';
                vehicleSelect.prepend(unavailable);
                vehicleStatus.textContent = user.vehicleType
                    ? `${user.vehicleType} is not supported yet. Coming soon. Choose Other to select a supported vehicle.`
                    : 'Vehicle type is unavailable. Coming soon. Choose Other to select a supported vehicle.';
                vehicleStatus.hidden = false;
            }
            document.getElementById('profileOtherVehicleType').hidden = true;
            document.getElementById('profileOtherVehicleType').required = false;
            document.getElementById('profileOtherVehicleType').value = '';
            document.getElementById('profileVehiclePlate').value = user.vehiclePlate || '';
            document.getElementById('profileVehiclePlate').disabled = !supportedType;
            const aadhaarInput = document.getElementById('profileAadhaar');
            aadhaarInput.value = '';
            aadhaarInput.type = 'password';
            aadhaarInput.parentElement.querySelector('[data-toggle-password="profileAadhaar"]').textContent = 'Show';
            document.getElementById('profileAadhaar').placeholder = user.aadhaarLoginEnabled
                ? `Linked Aadhaar ending in ${user.aadhaarLastFour} — enter a new number to replace`
                : user.aadhaarLastFour
                    ? `Last four on file: ${user.aadhaarLastFour} — enter full number to enable sign-in`
                    : 'Enter 12 digits to link Aadhaar sign-in';
        }
        const avatar = document.querySelector('.user-avatar-lg');
        avatar.replaceChildren();
        if (user.profileImageUrl) {
            const image = document.createElement('img');
            image.src = user.profileImageUrl;
            image.alt = `${user.name} profile`;
            image.referrerPolicy = 'no-referrer';
            avatar.appendChild(image);
        } else {
            const icon = document.createElement('i');
            icon.className = 'fa-solid fa-user';
            icon.setAttribute('aria-hidden', 'true');
            avatar.appendChild(icon);
        }
        const status = document.getElementById('driverOnlineToggle');
        if (status && user.role === 'DRIVER') status.checked = user.online;
        const statusText = document.getElementById('onlineStatusText');
        if (statusText && user.role === 'DRIVER') statusText.textContent = user.online ? 'ONLINE' : 'OFFLINE';
    }

    // Logouts
    document.getElementById('driverLogoutBtn').addEventListener('click', async () => {
        try {
            if (document.getElementById('driverOnlineToggle').checked) {
                await api('/driver/online', { method: 'PUT', body: JSON.stringify({ online: false }) });
            }
            await api('/logout', { method: 'POST' });
        } catch (error) {
            showToast(error.message);
        }
        window.clearInterval(driverRefreshTimer);
        if (driverWatchId !== null) navigator.geolocation.clearWatch(driverWatchId);
        driverWatchId = null;
        driverDashboardView.classList.remove('active');
        document.body.classList.remove('driver-portal-active');
        document.querySelector('.assistant-coming-soon').hidden = true;
        roleSelectView.classList.add('active');
    });

    document.getElementById('driverOnlineToggle').addEventListener('change', async (event) => {
        const statusText = document.getElementById('onlineStatusText');
        const toggle = event.target;
        const wantsOnline = toggle.checked;
        toggle.disabled = true;
        try {
            let position = null;
            if (wantsOnline) {
                if (!window.isSecureContext) {
                    throw new Error('GPS is blocked on this local address. On your phone, open the deployed Mithila Go site over HTTPS with a trusted certificate. Local network HTTP and self-signed HTTPS addresses cannot access GPS.');
                }
                if (!navigator.geolocation) {
                    throw new Error('This browser does not support location sharing. Open Mithila Go in a supported browser and allow location access.');
                }
                requestNotificationPermission();
                position = await getCurrentPosition();
            }
            const user = await api('/driver/online', { method: 'PUT', body: JSON.stringify({
                online: wantsOnline,
                latitude: position?.coords.latitude ?? null,
                longitude: position?.coords.longitude ?? null
            }) });
            statusText.textContent = user.online ? 'ONLINE' : 'OFFLINE';
            statusText.classList.toggle('offline', !user.online);
            if (user.online) startDriverLocationTracking();
            else stopDriverLocationTracking();
            refreshDriverRides();
        } catch (error) {
            toggle.checked = false;
            statusText.textContent = 'OFFLINE';
            statusText.classList.add('offline');
            showToast(error.message);
        } finally {
            toggle.disabled = false;
        }
    });

    function getCurrentPosition() {
        return new Promise((resolve, reject) => navigator.geolocation.getCurrentPosition(resolve, error => {
            reject(new Error(error.code === error.PERMISSION_DENIED
                ? 'Location permission was denied. Enable it in your browser settings, then try ONLINE again.'
                : error.code === error.POSITION_UNAVAILABLE
                    ? 'Your device could not determine a GPS location. Check that location services are enabled and try again.'
                    : 'Location timed out. Check that GPS is enabled, move to an open area, and try again.'));
        }, { enableHighAccuracy: true, maximumAge: 0, timeout: 15_000 }));
    }

    async function sendDriverPosition(position) {
        await api('/driver/location', { method: 'PUT', body: JSON.stringify({
            latitude: position.coords.latitude, longitude: position.coords.longitude
        }) });
    }

    function startDriverLocationTracking() {
        stopDriverLocationTracking();
        if (driverWatchId !== null) navigator.geolocation.clearWatch(driverWatchId);
        driverWatchId = navigator.geolocation.watchPosition(async position => {
            try {
                await sendDriverPosition(position);
            } catch (error) {
                showToast(error.message);
            }
        }, error => {
            showToast(error.code === error.PERMISSION_DENIED
                ? 'Location permission was removed. Enable it again or you will be set offline shortly.'
                : error.code === error.POSITION_UNAVAILABLE
                    ? 'GPS is unavailable. Check location services; you will be set offline if updates do not resume.'
                    : 'Location updates timed out. Keep this screen open; you will be set offline if they do not resume.');
            if (error.code === error.PERMISSION_DENIED) {
                api('/driver/online', { method: 'PUT', body: JSON.stringify({ online: false }) })
                    .then(user => {
                        document.getElementById('driverOnlineToggle').checked = user.online;
                        document.getElementById('onlineStatusText').textContent = 'OFFLINE';
                        document.getElementById('onlineStatusText').classList.add('offline');
                        stopDriverLocationTracking();
                    })
                    .catch(apiError => showToast(apiError.message));
            }
        }, { enableHighAccuracy: true, maximumAge: 10_000, timeout: 15_000 });
        driverLocationTimer = window.setInterval(async () => {
            try { await sendDriverPosition(await getCurrentPosition()); }
            catch (error) { showToast(error.message); }
        }, 10_000);
        if ('wakeLock' in navigator) {
            navigator.wakeLock.request('screen').then(lock => { wakeLock = lock; })
                .catch(() => showToast('Keep this screen on while online so location continues to update.'));
        } else {
            showToast('Keep this screen on while online so location continues to update.');
        }
    }

    function stopDriverLocationTracking() {
        if (driverWatchId !== null && navigator.geolocation) navigator.geolocation.clearWatch(driverWatchId);
        driverWatchId = null;
        window.clearInterval(driverLocationTimer);
        driverLocationTimer = null;
        if (wakeLock) wakeLock.release().catch(() => {});
        wakeLock = null;
    }

    async function refreshDriverRides() {
        const list = document.getElementById('driverIncomingList');
        const scheduledList = document.getElementById('driverScheduledList');
        if (list.querySelector('.driver-otp-input:focus')) {
            return;
        }
        try {
            const account = await api('/me');
            if (account) {
                const toggle = document.getElementById('driverOnlineToggle');
                const statusText = document.getElementById('onlineStatusText');
                toggle.checked = account.online;
                statusText.textContent = account.online ? 'ONLINE' : 'OFFLINE';
                statusText.classList.toggle('offline', !account.online);
                if (!account.online) stopDriverLocationTracking();
                else if (driverWatchId === null
                        && navigator.geolocation && window.isSecureContext) startDriverLocationTracking();
            }
            const rides = await api('/driver/rides');
            renderDriverMap(account, rides);
            list.replaceChildren();
            scheduledList.replaceChildren();
            const upcomingAcceptedRides = rides.filter(ride => ride.status === 'ASSIGNED'
                && ride.scheduledFor && Date.parse(ride.scheduledFor) > Date.now());
            const scheduledReminder = document.getElementById('scheduledRideReminder');
            scheduledReminder.hidden = upcomingAcceptedRides.length === 0;
            if (upcomingAcceptedRides.length) {
                const nextRide = upcomingAcceptedRides
                    .sort((first, second) => Date.parse(first.scheduledFor) - Date.parse(second.scheduledFor))[0];
                document.getElementById('scheduledRideReminderText').textContent =
                    `${upcomingAcceptedRides.length} accepted · next ${new Date(nextRide.scheduledFor).toLocaleString(undefined, {
                        weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit'
                    })}`;
            }
            rides.forEach(ride => {
                const targetList = ride.scheduledFor ? scheduledList : list;
                const card = document.createElement('article');
                card.className = 'incoming-ride-card';
                const top = document.createElement('div');
                top.className = 'ir-top';
                top.innerHTML = `<span><strong>${escapeHtml(ride.vehicleType)}</strong> · ${escapeHtml(ride.riderName)}</span><span class="fare-tag">₹${ride.fare}</span>`;
                const places = document.createElement('p');
                places.className = 'ride-route-copy';
                places.textContent = `${ride.pickup} → ${ride.destination}`;
                const status = document.createElement('p');
                status.className = 'ride-status-copy';
                status.textContent = ride.status.replace('_', ' ');
                if (ride.scheduledFor) {
                    const pickupTime = document.createElement('strong');
                    pickupTime.className = 'scheduled-ride-badge';
                    pickupTime.textContent = new Date(ride.scheduledFor).toLocaleString(undefined, {
                        weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit'
                    });
                    status.append(' · ', pickupTime);
                }
                card.append(top, places, status);
                const navigationTarget = ride.status === 'IN_PROGRESS'
                    ? (ride.dropoffLatitude != null && ride.dropoffLongitude != null
                        ? `${ride.dropoffLatitude},${ride.dropoffLongitude}` : ride.destination)
                    : (ride.pickupLatitude != null && ride.pickupLongitude != null
                        ? `${ride.pickupLatitude},${ride.pickupLongitude}` : ride.pickup);
                const navigation = document.createElement('a');
                navigation.className = 'driver-navigation-link';
                navigation.href = `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(navigationTarget)}&travelmode=driving`;
                navigation.target = '_blank';
                navigation.rel = 'noopener noreferrer';
                navigation.textContent = ride.status === 'IN_PROGRESS' ? 'Navigate to destination' : 'Navigate to pickup';
                card.appendChild(navigation);
                if (ride.status === 'REQUESTED') {
                    const actions = document.createElement('div');
                    actions.className = 'driver-ride-actions';
                    const accept = document.createElement('button');
                    accept.className = 'btn-primary';
                    accept.type = 'button';
                    accept.textContent = 'Accept ride';
                    accept.addEventListener('click', async () => {
                        accept.disabled = true;
                        try {
                            await api(`/driver/rides/${ride.id}/accept`, { method: 'POST' });
                            showToast('Ride accepted. Contact the rider and head to pickup.');
                            refreshDriverRides();
                        } catch (error) {
                            showToast(error.message);
                            refreshDriverRides();
                        }
                    });
                    const reject = document.createElement('button');
                    reject.type = 'button';
                    reject.className = 'btn-outline';
                    reject.textContent = 'Reject';
                    reject.addEventListener('click', async () => {
                        reject.disabled = true;
                        try {
                            await api(`/driver/rides/${ride.id}/reject`, { method: 'POST' });
                            showToast('Ride declined.');
                            refreshDriverRides();
                        } catch (error) {
                            showToast(error.message);
                            reject.disabled = false;
                            refreshDriverRides();
                        }
                    });
                    actions.append(accept, reject);
                    card.appendChild(actions);
                } else if (ride.status === 'ASSIGNED') {
                    const otpInput = document.createElement('input');
                    otpInput.className = 'driver-otp-input';
                    otpInput.type = 'text';
                    otpInput.inputMode = 'numeric';
                    otpInput.maxLength = 4;
                    otpInput.pattern = '[0-9]*';
                    otpInput.autocomplete = 'one-time-code';
                    otpInput.placeholder = 'Enter rider OTP';
                    otpInput.setAttribute('aria-label', 'Rider OTP');
                    const start = document.createElement('button');
                    start.className = 'btn-primary';
                    start.type = 'button';
                    start.textContent = 'Start ride';
                    start.addEventListener('click', async () => {
                        if (!/^\d{4}$/.test(otpInput.value)) {
                            otpInput.setCustomValidity('Enter the 4-digit code shared by the rider.');
                            otpInput.reportValidity();
                            return;
                        }
                        try {
                            await api(`/rides/${ride.id}/status?status=IN_PROGRESS&otp=${encodeURIComponent(otpInput.value)}`, { method: 'PATCH' });
                            showToast('Ride started.');
                            refreshDriverRides();
                        } catch (error) { showToast(error.message); }
                    });
                    otpInput.addEventListener('input', () => otpInput.setCustomValidity(''));
                    card.append(otpInput, start);
                } else if (ride.status === 'IN_PROGRESS') {
                    const complete = document.createElement('button');
                    complete.className = 'btn-primary';
                    complete.type = 'button';
                    complete.textContent = 'Complete ride';
                    complete.addEventListener('click', async () => {
                        try {
                            await api(`/rides/${ride.id}/status?status=COMPLETED`, { method: 'PATCH' });
                            showToast('Ride marked complete.');
                            refreshDriverRides();
                        } catch (error) { showToast(error.message); }
                    });
                    card.appendChild(complete);
                }
                targetList.appendChild(card);
            });
                if (!list.childElementCount) {
                    const message = !account?.online
                        ? window.isSecureContext
                            ? 'Go online to receive nearby ride requests.'
                            : 'Open the secure HTTPS site and allow location to go online and receive ride requests.'
                        : 'No instant rides are waiting.';
                    list.innerHTML = `<p class="empty-state">${message}</p>`;
                }
            if (!scheduledList.childElementCount) scheduledList.innerHTML = '<p class="empty-state">No scheduled rides.</p>';
            await pollNotifications();
        } catch (error) {
                list.replaceChildren();
                const errorMessage = document.createElement('p');
                errorMessage.className = 'empty-state';
                errorMessage.textContent = error.message || 'Could not refresh driver rides. Try again shortly.';
                list.appendChild(errorMessage);
                scheduledList.replaceChildren();
            document.getElementById('scheduledRideReminder').hidden = true;
        }
    }

    function renderDriverMap(account, rides) {
        const mapElement = document.getElementById('driverMap');
        if (!mapElement) return;
        if (!window.L) { mapElement.textContent = 'Map tiles are unavailable. Check your internet connection.'; return; }
        if (!driverMap) {
            driverMap = L.map(mapElement).setView([26.36, 86.07], 13);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                maxZoom: 19, attribution: '&copy; OpenStreetMap contributors'
            }).addTo(driverMap);
        }
        if (account?.lastLatitude != null && account?.lastLongitude != null) {
            const point = [account.lastLatitude, account.lastLongitude];
            if (!driverMarker) driverMarker = L.circleMarker(point, { radius: 8, color: '#1769d2', fillColor: '#3185f5', fillOpacity: 1 }).addTo(driverMap).bindPopup('Your location');
            driverMarker.setLatLng(point);
        }
        const currentRide = rides.find(ride => ride.status === 'IN_PROGRESS')
            || rides.find(ride => ride.status === 'ASSIGNED') || rides.find(ride => ride.status === 'REQUESTED');
        const mapStatus = document.getElementById('driverMapStatus');
        if (!currentRide || currentRide.pickupLatitude == null || currentRide.pickupLongitude == null) {
            if (mapStatus) mapStatus.textContent = '';
            if (driverRouteLine) { driverRouteLine.remove(); driverRouteLine = null; }
            if (driverPickupMarker) { driverPickupMarker.remove(); driverPickupMarker = null; }
            driverRouteKey = '';
            driverRouteContext = '';
            return;
        }
        const pickup = [currentRide.pickupLatitude, currentRide.pickupLongitude];
        if (!driverPickupMarker) driverPickupMarker = L.marker(pickup).addTo(driverMap).bindPopup('Rider pickup');
        driverPickupMarker.setLatLng(pickup);
        const from = currentRide.status === 'IN_PROGRESS' && currentRide.pickupLatitude != null
            ? [currentRide.pickupLatitude, currentRide.pickupLongitude] : [account?.lastLatitude, account?.lastLongitude];
        const to = currentRide.status === 'IN_PROGRESS' && currentRide.dropoffLatitude != null
            ? [currentRide.dropoffLatitude, currentRide.dropoffLongitude] : pickup;
        if (!from.every(Number.isFinite) || !to.every(Number.isFinite)) return;
        const rounded = point => point.map(value => value.toFixed(3)).join(',');
        const key = `${currentRide.id}:${currentRide.status}:${rounded(from)}:${rounded(to)}`;
        if (key === driverRouteKey) return;
        const context = `${currentRide.id}:${currentRide.status}`;
        if (context === driverRouteContext && Date.now() - driverRouteRequestedAt < 30_000) return;
        driverRouteKey = key;
        driverRouteContext = context;
        driverRouteRequestedAt = Date.now();
        if (mapStatus) mapStatus.textContent = 'Finding route...';
        const bounds = [from, to];
        driverMap.fitBounds(bounds, { padding: [20, 20] });
        const vehicleType = account?.vehicleType || currentRide.vehicleType;
        const params = new URLSearchParams({ vehicleType, pickupLatitude: from[0], pickupLongitude: from[1],
            dropoffLatitude: to[0], dropoffLongitude: to[1] });
        api(`/estimate?${params}`).then(quote => {
            if (driverRouteKey !== key) return;
            if (driverRouteLine) driverRouteLine.remove();
            if (!quote.routeCoordinates || quote.routeCoordinates.length < 2) {
                if (mapStatus) mapStatus.textContent = 'Road route unavailable. Showing the location markers only.';
                return;
            }
            const points = quote.routeCoordinates.map(point => [point[1], point[0]]);
            driverRouteLine = L.polyline(points, {
                color: '#2563eb', weight: 5, dashArray: quote.approximate ? '7 7' : null
            }).addTo(driverMap);
            if (mapStatus) {
                mapStatus.textContent = currentRide.status === 'IN_PROGRESS'
                    ? `To destination · ${quote.distanceKm} km · ${quote.durationMinutes} min${quote.approximate ? ' approx.' : ''}`
                    : `To pickup · ${quote.distanceKm} km · ${quote.durationMinutes} min${quote.approximate ? ' approx.' : ''}`;
            }
        }).catch(error => {
            if (driverRouteKey === key) {
                if (mapStatus) mapStatus.textContent = error.message;
                driverRouteKey = '';
            }
        });
    }

    function renderActiveRideMap(ride) {
        const mapElement = document.getElementById('activeRideMap');
        if (!window.L || !mapElement) return;
        if (!activeRideMap) {
            activeRideMap = L.map(mapElement).setView([26.36, 86.07], 13);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                maxZoom: 19, attribution: '&copy; OpenStreetMap contributors'
            }).addTo(activeRideMap);
        }
        const validPoint = (latitude, longitude) => Number.isFinite(latitude) && Number.isFinite(longitude)
            && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
        const pickup = validPoint(ride.pickupLatitude, ride.pickupLongitude)
            ? [ride.pickupLatitude, ride.pickupLongitude] : null;
        const drop = validPoint(ride.dropoffLatitude, ride.dropoffLongitude)
            ? [ride.dropoffLatitude, ride.dropoffLongitude] : null;
        if (pickup) {
            if (!activePickupMarker) activePickupMarker = L.marker(pickup).addTo(activeRideMap).bindPopup('Pickup');
            activePickupMarker.setLatLng(pickup);
        }
        if (drop) {
            if (!activeDropMarker) activeDropMarker = L.marker(drop).addTo(activeRideMap).bindPopup('Destination');
            activeDropMarker.setLatLng(drop);
        }
        const driver = validPoint(ride.driverLatitude, ride.driverLongitude)
            ? [ride.driverLatitude, ride.driverLongitude] : null;
        const driverKind = ride.status === 'IN_PROGRESS' ? 'in-progress' : 'assigned';
        if (driver) {
            const icon = L.divIcon({
                className: 'live-driver-icon',
                html: `<span class="live-driver-marker ${driverKind}"><span class="live-driver-pulse"></span><span class="live-driver-symbol"><i class="fa-solid fa-car-side"></i></span></span>`,
                iconSize: [42, 42],
                iconAnchor: [21, 21]
            });
            if (!activeDriverMarker) {
                activeDriverMarker = L.marker(driver, { icon, title: 'Driver live location' }).addTo(activeRideMap)
                    .bindPopup('Driver live location');
                activeDriverMarkerKind = driverKind;
            } else {
                if (activeDriverMarkerKind !== driverKind) {
                    activeDriverMarker.setIcon(icon);
                    activeDriverMarkerKind = driverKind;
                }
                moveActiveDriverMarker(driver);
            }
        } else if (activeDriverMarker) {
            activeDriverMarker.remove();
            activeDriverMarker = null;
            activeDriverMarkerKind = '';
            if (activeDriverAnimationFrame !== null) {
                window.cancelAnimationFrame(activeDriverAnimationFrame);
                activeDriverAnimationFrame = null;
            }
        }
        const inProgress = ride.status === 'IN_PROGRESS';
        const from = inProgress ? pickup : driver;
        const to = inProgress ? drop : pickup;
        const notice = document.getElementById('activeMapNotice');
        const mapContext = `${ride.id}:${ride.status}:${driver ? 'live' : 'waiting'}`;
        if (!from || !to) {
            if (activeRouteLine) {
                activeRouteLine.remove();
                activeRouteLine = null;
            }
            activeRouteKey = '';
            activeRouteContext = '';
            activeRouteQuote = null;
            notice.hidden = false;
            notice.textContent = !pickup || !drop
                ? 'A mapped pickup or destination is unavailable; ride status will continue updating.'
                : 'Waiting for the driver’s live location. The map will update when it is available.';
            if (mapContext !== activeRideMapContext) {
                const availablePoint = driver || pickup || drop;
                if (availablePoint) activeRideMap.setView(availablePoint, 15);
                activeRideMapContext = mapContext;
            } else if (driver && !activeRideMap.getBounds().contains(driver)) {
                activeRideMap.panTo(driver);
            }
            return;
        }
        notice.hidden = Boolean(driver);
        if (!driver) {
            notice.textContent = 'Waiting for the driver’s live location. The route and ride status remain available.';
        }
        if (mapContext !== activeRideMapContext) {
            activeRideMap.fitBounds([from, to, ...(driver ? [driver] : [])], { padding: [18, 18] });
            activeRideMapContext = mapContext;
        } else if (driver && !activeRideMap.getBounds().contains(driver)) {
            activeRideMap.panTo(driver);
        }
        const rounded = point => point.map(value => value.toFixed(3)).join(',');
        const routeContext = `${ride.id}:${ride.status}`;
        if (routeContext === activeRouteContext && Date.now() - activeRouteRequestedAt < 30_000) return;
        if (routeContext !== activeRouteContext) activeRouteQuote = null;
        const routeKey = `${ride.id}:${ride.status}:${rounded(from)}:${rounded(to)}:${Date.now()}`;
        activeRouteKey = routeKey;
        if (activeRouteLine) {
            activeRouteLine.remove();
            activeRouteLine = null;
        }
        activeRouteContext = routeContext;
        activeRouteRequestedAt = Date.now();
        const vehicleType = ride.vehicleType || 'Mithila Auto';
        const params = new URLSearchParams({ vehicleType, pickupLatitude: from[0], pickupLongitude: from[1],
            dropoffLatitude: to[0], dropoffLongitude: to[1] });
        api(`/estimate?${params}`).then(quote => {
            if (activeRouteKey !== routeKey) return;
            if (!quote.routeCoordinates || quote.routeCoordinates.length < 2) {
                activeRouteQuote = null;
                notice.hidden = false;
                notice.textContent = 'Road route unavailable; showing the available live locations only.';
                updateRideStatus(ride);
                return;
            }
            activeRouteQuote = quote;
            const points = quote.routeCoordinates.map(point => [point[1], point[0]]);
            activeRouteLine = L.polyline(points, { color: '#ef5b36', weight: 5 }).addTo(activeRideMap);
            updateRideStatus(ride);
        }).catch(() => {
            if (activeRouteKey === routeKey) {
                activeRouteQuote = null;
                notice.hidden = false;
                notice.textContent = 'Route details unavailable; showing the available live locations only.';
                updateRideStatus(ride);
            }
        });
    }

    function moveActiveDriverMarker(target) {
        if (!activeDriverMarker) return;
        const marker = activeDriverMarker;
        const start = marker.getLatLng();
        const startPoint = [start.lat, start.lng];
        const distance = distanceBetweenLocations(
            { latitude: startPoint[0], longitude: startPoint[1] },
            { latitude: target[0], longitude: target[1] }
        );
        if (!Number.isFinite(distance) || distance < 0.003
                || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            marker.setLatLng(target);
            return;
        }

        if (activeDriverAnimationFrame !== null) window.cancelAnimationFrame(activeDriverAnimationFrame);
        const fromLatitude = startPoint[0] * Math.PI / 180;
        const toLatitude = target[0] * Math.PI / 180;
        const longitudeDelta = (target[1] - startPoint[1]) * Math.PI / 180;
        const y = Math.sin(longitudeDelta) * Math.cos(toLatitude);
        const x = Math.cos(fromLatitude) * Math.sin(toLatitude)
            - Math.sin(fromLatitude) * Math.cos(toLatitude) * Math.cos(longitudeDelta);
        const heading = Math.atan2(y, x) * 180 / Math.PI - 90;
        marker.getElement()?.querySelector('.live-driver-symbol')
            ?.style.setProperty('--driver-heading', `${heading}deg`);
        const startedAt = performance.now();
        const duration = 1800;
        const animate = now => {
            const progress = Math.min(1, (now - startedAt) / duration);
            const eased = progress * progress * (3 - 2 * progress);
            marker.setLatLng([
                startPoint[0] + (target[0] - startPoint[0]) * eased,
                startPoint[1] + (target[1] - startPoint[1]) * eased
            ]);
            if (progress < 1) {
                activeDriverAnimationFrame = window.requestAnimationFrame(animate);
            } else {
                activeDriverAnimationFrame = null;
            }
        };
        activeDriverAnimationFrame = window.requestAnimationFrame(animate);
    }

    function updateRideStatus(ride) {
        const panel = document.getElementById('rideStatusPanel');
        const label = document.getElementById('tripLiveLabel');
        const status = document.getElementById('tripLiveStatus');
        const detail = document.getElementById('tripLiveDetail');
        const icon = document.getElementById('tripLiveIcon');
        const validPoint = (latitude, longitude) => Number.isFinite(latitude) && Number.isFinite(longitude)
            && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
        const hasDriver = validPoint(ride.driverLatitude, ride.driverLongitude);
        panel.dataset.status = ride.status.toLowerCase();
        panel.classList.toggle('has-live-driver', hasDriver);
        if (ride.status === 'IN_PROGRESS') {
            status.textContent = 'Your ride is in progress';
            label.textContent = 'RIDE IN PROGRESS';
            detail.textContent = hasDriver
                ? 'Your driver’s position is updating on the map.'
                : 'Trip started · waiting for the driver’s next GPS update.';
            icon.className = 'fa-solid fa-route';
            return;
        }
        if (ride.status !== 'ASSIGNED') return;
        label.textContent = 'DRIVER ACCEPTED';
        icon.className = 'fa-solid fa-car-side';
        if (!hasDriver || !validPoint(ride.pickupLatitude, ride.pickupLongitude)) {
            status.textContent = 'Your driver is on the way to pickup';
            detail.textContent = hasDriver
                ? 'Waiting for a mapped pickup location.'
                : 'Waiting for the driver to share a live location.';
            return;
        }
        const distanceKm = distanceBetweenLocations(
            { latitude: ride.driverLatitude, longitude: ride.driverLongitude },
            { latitude: ride.pickupLatitude, longitude: ride.pickupLongitude }
        );
        if (distanceKm <= 0.25) {
            status.textContent = 'Your driver is approaching pickup';
            detail.textContent = `About ${Math.round(distanceKm * 1000)} m away by GPS`;
        } else if (distanceKm <= 1) {
            status.textContent = 'Your driver is approaching pickup';
            detail.textContent = `About ${Math.round(distanceKm * 1000)} m away by GPS`;
        } else {
            status.textContent = 'Your driver is on the way to pickup';
            detail.textContent = `About ${distanceKm.toFixed(1)} km away by GPS`;
        }
        if (activeRouteContext === `${ride.id}:${ride.status}` && activeRouteQuote) {
            detail.textContent = `About ${activeRouteQuote.distanceKm} km by road · ${activeRouteQuote.durationMinutes} min estimated`;
        }
    }

    function clearActiveRideMap() {
        [activeDriverMarker, activePickupMarker, activeDropMarker, activeRouteLine].forEach(layer => layer?.remove());
        activeDriverMarker = null;
        activeDriverMarkerKind = '';
        if (activeDriverAnimationFrame !== null) window.cancelAnimationFrame(activeDriverAnimationFrame);
        activeDriverAnimationFrame = null;
        activePickupMarker = null;
        activeDropMarker = null;
        activeRouteLine = null;
        activeRouteKey = '';
        activeRouteContext = '';
        activeRouteRequestedAt = 0;
        activeRouteQuote = null;
        activeRideMapContext = '';
        document.getElementById('activeMapNotice').hidden = true;
    }

    async function loadVehicles() {
        try {
            availableVehicles = await api('/vehicles');
            triggerCabCalculation();
        } catch (error) {
            showToast(error.message);
        }
    }

    function escapeHtml(value) {
        return String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
    }

    document.getElementById('userLogoutLink').addEventListener('click', async () => {
        try {
            await api('/logout', { method: 'POST' });
        } catch (error) {
            showToast(error.message);
        }
        window.clearInterval(ridePollTimer);
        userMainApp.classList.remove('active');
        document.querySelector('.assistant-coming-soon').hidden = true;
        roleSelectView.classList.add('active');
        currentUser = null;
        document.getElementById('profileModal').classList.remove('open');
        document.getElementById('profileModalOverlay').classList.remove('open');
    });

    // ================= 2. RIDER SIDEBAR & MODALS =================
    const sideDrawer = document.getElementById('sideDrawer');
    const drawerOverlay = document.getElementById('drawerOverlay');
    document.getElementById('menuBtn').addEventListener('click', () => { sideDrawer.classList.add('open'); drawerOverlay.classList.add('open'); });
    document.getElementById('closeDrawerBtn').addEventListener('click', () => { sideDrawer.classList.remove('open'); drawerOverlay.classList.remove('open'); });
    drawerOverlay.addEventListener('click', () => { sideDrawer.classList.remove('open'); drawerOverlay.classList.remove('open'); });

    document.querySelectorAll('.drawer-item').forEach(item => {
        item.addEventListener('click', () => {
            document.querySelectorAll('.drawer-item').forEach(i => i.classList.remove('active'));
            item.classList.add('active');
            const target = item.getAttribute('data-view');
            document.querySelectorAll('#userMainApp .view-section').forEach(sec => sec.classList.remove('active'));
            if(target === 'home') document.getElementById('homeView').classList.add('active');
            if(target === 'rides') {
                document.getElementById('ridesView').classList.add('active');
                loadRidesHistory();
            }
            if(target === 'settings') document.getElementById('settingsView').classList.add('active');
            if(target === 'support') document.getElementById('supportView').classList.add('active');
            sideDrawer.classList.remove('open');
            drawerOverlay.classList.remove('open');
        });
    });

    document.getElementById('profileBtn').addEventListener('click', () => {
        document.getElementById('profileModal').classList.add('open');
        document.getElementById('profileModalOverlay').classList.add('open');
    });
    document.getElementById('driverProfileBtn').addEventListener('click', () => {
        document.getElementById('profileModal').classList.add('open');
        document.getElementById('profileModalOverlay').classList.add('open');
    });
    document.getElementById('closeProfileModal').addEventListener('click', () => {
        document.getElementById('profileModal').classList.remove('open');
        document.getElementById('profileModalOverlay').classList.remove('open');
        const aadhaarInput = document.getElementById('profileAadhaar');
        aadhaarInput.value = '';
        aadhaarInput.type = 'password';
        aadhaarInput.parentElement.querySelector('[data-toggle-password="profileAadhaar"]').textContent = 'Show';
    });

    document.getElementById('profileVehicleType').addEventListener('change', event => {
        const selected = event.target.value;
        const otherVehicle = document.getElementById('profileOtherVehicleType');
        const supportedType = supportedDriverVehicle(selected);
        otherVehicle.hidden = selected !== 'OTHER';
        otherVehicle.required = selected === 'OTHER';
        if (selected !== 'OTHER') otherVehicle.value = '';
        document.getElementById('profileVehicleStatus').hidden = Boolean(supportedType) || selected === 'OTHER';
        document.getElementById('profileVehiclePlate').disabled = !supportedType && selected !== 'OTHER';
    });

    document.getElementById('profileEditForm').addEventListener('submit', async event => {
        event.preventDefault();
        const newPassword = document.getElementById('profileNewPassword').value;
        const confirmPassword = document.getElementById('profileConfirmPassword').value;
        if (newPassword && newPassword !== confirmPassword) {
            document.getElementById('profileConfirmPassword').setCustomValidity('Passwords do not match.');
            document.getElementById('profileConfirmPassword').reportValidity();
            return;
        }
        const button = document.getElementById('saveProfileBtn');
        setBusy(button, true, 'Saving...');
        const profileVehicleSelect = document.getElementById('profileVehicleType');
        const selectedProfileVehicle = profileVehicleSelect.value === 'OTHER'
            ? document.getElementById('profileOtherVehicleType').value
            : profileVehicleSelect.selectedOptions[0]?.disabled ? null : profileVehicleSelect.value;
        try {
            const updated = await api('/profile', {
                method: 'PUT',
                body: JSON.stringify({
                    name: document.getElementById('profileName').value.trim(),
                    phone: document.getElementById('profilePhone').value.trim(),
                    vehicleType: currentUser?.role === 'DRIVER' ? selectedProfileVehicle : null,
                    vehiclePlate: currentUser?.role === 'DRIVER' && !document.getElementById('profileVehiclePlate').disabled
                        ? document.getElementById('profileVehiclePlate').value.trim() : null,
                    aadhaarNumber: currentUser?.role === 'DRIVER'
                        ? document.getElementById('profileAadhaar').value.trim() || null : null,
                    currentPassword: document.getElementById('profileCurrentPassword').value,
                    newPassword: newPassword || null,
                    confirmPassword: confirmPassword || null
                })
            });
            applyUser(updated);
            ['profileCurrentPassword', 'profileNewPassword', 'profileConfirmPassword']
                .forEach(id => { document.getElementById(id).value = ''; });
            document.getElementById('profileModal').classList.remove('open');
            document.getElementById('profileModalOverlay').classList.remove('open');
            showToast('Profile updated.');
        } catch (error) {
            showToast(error.message);
        } finally {
            setBusy(button, false);
        }
    });
    document.getElementById('profileConfirmPassword').addEventListener('input', event => {
        event.target.setCustomValidity('');
    });

    document.getElementById('emergencySOSBtn').addEventListener('click', () => {
        showToast('Emergency services are not connected in this demo. Call local emergency services if needed.');
    });

    document.querySelectorAll('[data-support]').forEach(button => {
        button.addEventListener('click', () => {
            showToast(button.dataset.support === 'agent'
                ? 'Support chat is not connected in this demo.'
                : 'Safety information will be available here soon.');
        });
    });

    document.getElementById('inviteFriendsBtn').addEventListener('click', () => {
        showToast('Share code MITHILA50 with friends for ride offers.');
    });

    document.getElementById('homeLogoBtn').addEventListener('click', () => {
        document.querySelectorAll('#userMainApp .view-section').forEach(section => section.classList.remove('active'));
        homeView.classList.add('active');
        document.querySelectorAll('.drawer-item').forEach(item => item.classList.toggle('active', item.dataset.view === 'home'));
    });

    // ================= 3. CAB SEARCH & FARE CALCULATION =================
    const dropInput = document.getElementById('dropInput');
    const pickupInput = document.getElementById('pickupInput');
    const suggestionsBox = document.getElementById('suggestionsBox');
    const cabOptionsContainer = document.getElementById('cabOptionsContainer');
    const cabList = document.getElementById('cabList');
    const bookRideBtn = document.getElementById('bookRideBtn');

    function distanceBetweenLocations(first, second) {
        const toRadians = value => value * Math.PI / 180;
        const latitudeDelta = toRadians(second.latitude - first.latitude);
        const longitudeDelta = toRadians(second.longitude - first.longitude);
        const a = Math.sin(latitudeDelta / 2) ** 2
            + Math.cos(toRadians(first.latitude)) * Math.cos(toRadians(second.latitude))
            * Math.sin(longitudeDelta / 2) ** 2;
        return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    function nearestKnownLandmark(coordinates) {
        return availableLandmarks
            .map(place => ({ place, distance: distanceBetweenLocations(coordinates, place) }))
            .filter(item => item.distance <= 2)
            .sort((first, second) => first.distance - second.distance)[0]?.place || null;
    }

    async function resolveLocationLabel(which, coordinates, fallbackLabel) {
        const requestId = ++locationLabelRequests[which];
        const input = which === 'pickup' ? pickupInput : dropInput;
        if (which === 'pickup') pickupLabelPending = true;
        else destinationLabelPending = true;
        const isCurrentSelection = () => {
            const current = which === 'pickup' ? pickupCoordinates : destinationCoordinates;
            return locationLabelRequests[which] === requestId
                && current?.latitude === coordinates.latitude && current?.longitude === coordinates.longitude;
        };
        try {
            const params = new URLSearchParams({
                latitude: coordinates.latitude,
                longitude: coordinates.longitude
            });
            const location = await api(`/geocode/reverse?${params}`);
            if (!isCurrentSelection()) return;
            input.value = location.name;
            if (which === 'pickup') {
                document.getElementById('locationStatus').textContent =
                    `Pickup set to ${location.name}. The green pin marks the selected GPS point.`;
            }
        } catch {
            if (!isCurrentSelection()) return;
            input.value = '';
            if (which === 'pickup') {
                document.getElementById('locationStatus').textContent =
                    'Could not identify this GPS location. Try again or enter a pickup address manually.';
            } else {
                showToast('Could not identify that destination. Enter a different address and try again.');
            }
        } finally {
            if (isCurrentSelection()) {
                if (which === 'pickup') pickupLabelPending = false;
                else destinationLabelPending = false;
                triggerCabCalculation();
            }
        }
    }

    document.getElementById('useMyLocationBtn').addEventListener('click', async event => {
        const button = event.currentTarget;
        if (!navigator.geolocation) {
            showToast('Location is unavailable in this browser. Choose a landmark or place a pickup pin.');
            return;
        }
        if (!window.isSecureContext) {
            showToast('Location needs HTTPS or localhost. Choose a landmark or place a pickup pin.');
            return;
        }
        button.disabled = true;
        button.textContent = 'Finding location...';
        try {
            const position = await new Promise((resolve, reject) => {
                navigator.geolocation.getCurrentPosition(resolve, reject, {
                    enableHighAccuracy: true, maximumAge: 0, timeout: 15_000
                });
            });
            pickupCoordinates = {
                latitude: position.coords.latitude,
                longitude: position.coords.longitude
            };
            currentLocationCoordinates = pickupCoordinates;
            pickupInput.value = 'Finding your nearby address...';
            document.getElementById('locationStatus').textContent =
                `GPS fix received (about ±${Math.round(position.coords.accuracy || 0)} m accuracy). Identifying the address...`;
            await resolveLocationLabel('pickup', pickupCoordinates, 'GPS location');
            selectedCabType = null;
            triggerCabCalculation();
        } catch (error) {
            showToast(error.code === error.PERMISSION_DENIED
                ? 'Location permission denied. Allow it in your browser settings, or enter a pickup address manually.'
                : error.code === error.TIMEOUT
                    ? 'Location timed out. Try again or enter a pickup address manually.'
                    : 'Could not get your location. Enter a pickup address manually.');
        } finally {
            button.disabled = false;
            button.textContent = 'Use my current location';
        }
    });

    async function refreshFareQuote() {
        if (!selectedCabType) return;
        const requestId = ++fareQuoteRequest;
        const params = new URLSearchParams({ vehicleType: selectedCabType });
        const hasCoordinates = pickupCoordinates && destinationCoordinates;
        if (hasCoordinates) {
            params.set('pickupLatitude', pickupCoordinates.latitude);
            params.set('pickupLongitude', pickupCoordinates.longitude);
            params.set('dropoffLatitude', destinationCoordinates.latitude);
            params.set('dropoffLongitude', destinationCoordinates.longitude);
        }
        try {
            const quote = await api(`/estimate?${params}`);
            if (requestId !== fareQuoteRequest || selectedCabType == null) return;
            selectedFare = quote.fare;
            if (!hasCoordinates) {
                document.getElementById('fareEstimateLabel').textContent = `Estimated fare from ₹${quote.fare} · approximate until both locations are shared`;
                return;
            }
            document.getElementById('fareEstimateLabel').textContent = `Estimated ${quote.distanceKm} km · ${quote.durationMinutes} min · ₹${quote.fare}${quote.approximate ? ' · approximate' : ''}`;
        } catch (error) {
            document.getElementById('fareEstimateLabel').textContent = error.message;
        }
    }

    function selectSuggestion(location, coordinates) {
        window.clearTimeout(locationSearchTimer);
        locationSearchRequest++;
        locationLabelRequests.pickup++;
        locationLabelRequests.dropoff++;
        pickupLabelPending = false;
        destinationLabelPending = false;
        const selectedCoordinates = coordinates || null;
        if (activeLocationInput === pickupInput) {
            pickupInput.value = location;
            pickupCoordinates = selectedCoordinates;
            document.getElementById('locationStatus').textContent = 'Pickup location selected and ready.';
        } else {
            dropInput.value = location;
            destinationCoordinates = selectedCoordinates;
        }
        suggestionsBox.style.display = 'none';
        selectedCabType = null;
        selectedFare = 0;
        triggerCabCalculation();
    }

    async function loadLandmarks() {
        try {
            availableLandmarks = await api('/landmarks');
            if (activeLocationInput) filterSuggestions(activeLocationInput);
        } catch (error) {
            showToast(error.message);
        }
    }

    loadLandmarks();

    async function resolveTypedLocation(input, which) {
        const query = input.value.trim();
        if (query.length < 3) return;
        const coordinates = which === 'pickup' ? pickupCoordinates : destinationCoordinates;
        if (coordinates) return;
        const requestId = ++locationSearchRequest;
        if (which === 'pickup') {
            document.getElementById('locationStatus').textContent = 'Identifying the typed pickup address...';
        }
        try {
            const params = new URLSearchParams({ query });
            const results = await api(`/geocode/search?${params}`);
            if (requestId !== locationSearchRequest || input.value.trim() !== query) return;
            const match = results[0];
            if (!match) throw new Error('No matching address was found.');
            const point = { latitude: match.latitude, longitude: match.longitude };
            if (which === 'pickup') {
                pickupCoordinates = point;
                document.getElementById('locationStatus').textContent = `Pickup location identified as ${match.name}.`;
            } else {
                destinationCoordinates = point;
            }
            remoteLocationSuggestions = results;
            selectedCabType = null;
            triggerCabCalculation();
        } catch {
            if (requestId !== locationSearchRequest || input.value.trim() !== query) return;
            if (which === 'pickup') {
                document.getElementById('locationStatus').textContent =
                    'Address not identified yet. Select a suggestion or enter a more specific address.';
            }
        }
    }

    function renderLocationSuggestions(input, query, lookupError = '') {
        suggestionsBox.replaceChildren();
        const anchor = input === pickupInput
            ? currentLocationCoordinates
            : pickupCoordinates || currentLocationCoordinates;
        const matches = [...availableLandmarks, ...remoteLocationSuggestions]
            .filter(place => !query
                || `${place.name} ${place.area || ''}`.toLocaleLowerCase().includes(query.toLocaleLowerCase()))
            .map((place, index) => ({
                place,
                index,
                distance: anchor ? distanceBetweenLocations(anchor, place) : Number.POSITIVE_INFINITY
            }))
            .filter(item => !anchor || item.distance <= 30)
            .sort((first, second) => first.distance - second.distance || first.index - second.index);
        const seen = new Set();
        const uniqueMatches = matches.filter(({ place }) => {
            const key = `${place.name.toLocaleLowerCase()}|${place.latitude.toFixed(5)}|${place.longitude.toFixed(5)}`;
            if (seen.has(key)) return false;
            seen.add(key);
            return true;
        }).slice(0, 10);
        uniqueMatches.forEach(({ place }) => {
            const suggestion = document.createElement('button');
            suggestion.type = 'button';
            suggestion.className = 'suggestion-item';
            suggestion.setAttribute('role', 'option');
            suggestion.textContent = place.area ? `${place.name} · ${place.area}` : place.name;
            suggestion.addEventListener('click', () => selectSuggestion(place.name, {
                latitude: place.latitude,
                longitude: place.longitude
            }));
            suggestionsBox.appendChild(suggestion);
        });
        if (lookupError) {
            const error = document.createElement('p');
            error.className = 'suggestion-error';
            error.textContent = lookupError;
            suggestionsBox.appendChild(error);
        } else if (locationSearchLoading && query.length >= 3 && !uniqueMatches.length) {
            const pending = document.createElement('p');
            pending.className = 'suggestion-error';
            pending.textContent = 'Searching nearby locations...';
            suggestionsBox.appendChild(pending);
        } else if (!uniqueMatches.length && query.length >= 3) {
            const empty = document.createElement('p');
            empty.className = 'suggestion-error';
            empty.textContent = 'No nearby matches. Try a different place name or search for a type of place.';
            suggestionsBox.appendChild(empty);
        }
        suggestionsBox.style.display = suggestionsBox.childElementCount ? 'block' : 'none';
    }

    function filterSuggestions(input) {
        activeLocationInput = input;
        const query = input.value.trim();
        remoteLocationSearchError = '';
        locationSearchLoading = false;
        renderLocationSuggestions(input, query);
        window.clearTimeout(locationSearchTimer);
        const requestId = ++locationSearchRequest;
        if (query.length < 3) {
            remoteLocationSuggestions = [];
            renderLocationSuggestions(input, query);
            return;
        }
        locationSearchLoading = true;
        renderLocationSuggestions(input, query);
        locationSearchTimer = window.setTimeout(async () => {
            const params = new URLSearchParams({ query });
            const anchor = input === pickupInput
                ? currentLocationCoordinates
                : pickupCoordinates || currentLocationCoordinates;
            if (anchor) {
                params.set('latitude', anchor.latitude);
                params.set('longitude', anchor.longitude);
            }
            try {
                const results = await api(`/geocode/search?${params}`);
                if (requestId !== locationSearchRequest || activeLocationInput !== input
                        || input.value.trim() !== query) return;
                remoteLocationSuggestions = results;
                locationSearchLoading = false;
                renderLocationSuggestions(input, query);
            } catch {
                if (requestId !== locationSearchRequest || activeLocationInput !== input
                        || input.value.trim() !== query) return;
                remoteLocationSuggestions = [];
                remoteLocationSearchError =
                    'Address search is unavailable. Enter a more specific address or try again.';
                locationSearchLoading = false;
                renderLocationSuggestions(input, query, remoteLocationSearchError);
            }
        }, 900);
    }

    dropInput.addEventListener('focus', () => filterSuggestions(dropInput));
    dropInput.addEventListener('blur', () => resolveTypedLocation(dropInput, 'dropoff'));
    dropInput.addEventListener('input', () => {
        selectedCabType = null;
        selectedFare = 0;
        destinationLabelPending = false;
        locationLabelRequests.dropoff++;
        destinationCoordinates = null;
        filterSuggestions(dropInput);
        triggerCabCalculation();
    });

    pickupInput.addEventListener('focus', () => filterSuggestions(pickupInput));
    pickupInput.addEventListener('blur', () => resolveTypedLocation(pickupInput, 'pickup'));
    pickupInput.addEventListener('input', () => {
        selectedCabType = null;
        selectedFare = 0;
        pickupLabelPending = false;
        const query = pickupInput.value.trim().toLocaleLowerCase();
        const landmark = availableLandmarks.find(place => place.name.toLocaleLowerCase() === query);
        pickupCoordinates = landmark
            ? { latitude: landmark.latitude, longitude: landmark.longitude }
            : null;
        locationLabelRequests.pickup++;
        if (query) {
            document.getElementById('locationStatus').textContent = landmark
                ? 'Pickup landmark selected.'
                : 'Select a location suggestion or place a pickup pin to confirm the exact point.';
        } else {
            document.getElementById('locationStatus').textContent =
                'Choose a Madhubani or Mithila location from the suggestions.';
        }
        filterSuggestions(pickupInput);
        triggerCabCalculation();
    });

    document.addEventListener('click', (event) => {
        if (!event.target.closest('#homeView .ride-form') && !event.target.closest('#suggestionsBox')) {
            suggestionsBox.style.display = 'none';
        }
    });

    function triggerCabCalculation() {
        const destination = dropInput.value.trim();
        if (!pickupInput.value.trim() || !destination) {
            cabOptionsContainer.style.display = 'none';
            cabList.innerHTML = '';
            bookRideBtn.disabled = true;
            bookRideBtn.textContent = 'Choose a pickup and destination';
            return;
        }

        cabOptionsContainer.style.display = 'flex';
        if (!availableVehicles.length) {
            cabList.innerHTML = '<p class="empty-state">Checking vehicle availability…</p>';
            bookRideBtn.disabled = true;
            return;
        }
        const icons = { 'Mithila Bike': 'fa-motorcycle', 'Mithila Auto': 'fa-taxi',
            'Mithila E-Rickshaw': 'fa-rickshaw', 'Mithila Mini': 'fa-car-side', 'Mithila SUV': 'fa-car' };

        cabList.innerHTML = '';
        availableVehicles.forEach(cab => {
            cab.fare = '…';
            const available = cab.registeredDrivers > 0;
            const online = cab.onlineDrivers > 0;
            const card = document.createElement('button');
            card.type = 'button';
            card.className = `cab-card${cab.vehicleType === selectedCabType ? ' selected' : ''}${available ? '' : ' cab-card-unavailable'}`;
            card.disabled = !available;
            card.innerHTML = `
                <span class="cab-info"><i class="fa-solid ${icons[cab.vehicleType] || 'fa-car'}"></i><span><strong>${cab.vehicleType}</strong><small>${!available ? 'Coming soon' : online ? 'Drivers available' : 'No driver available right now'}</small></span></span>
                <span class="cab-price">₹${cab.fare}</span>
            `;
            card.addEventListener('click', () => {
                if (!available) return;
                document.querySelectorAll('.cab-card').forEach(c => c.classList.remove('selected'));
                card.classList.add('selected');
                selectedCabType = cab.vehicleType;
                bookRideBtn.disabled = !pickupCoordinates || !destinationCoordinates
                    || pickupLabelPending || destinationLabelPending;
                bookRideBtn.textContent = pickupCoordinates && destinationCoordinates
                    && !pickupLabelPending && !destinationLabelPending
                    ? `Book ${cab.vehicleType}` : 'Select both locations first';
                refreshFareQuote();
            });
            cabList.appendChild(card);
            if (available) {
                const fareParams = new URLSearchParams({ vehicleType: cab.vehicleType });
                if (pickupCoordinates && destinationCoordinates) {
                    fareParams.set('pickupLatitude', pickupCoordinates.latitude);
                    fareParams.set('pickupLongitude', pickupCoordinates.longitude);
                    fareParams.set('dropoffLatitude', destinationCoordinates.latitude);
                    fareParams.set('dropoffLongitude', destinationCoordinates.longitude);
                }
                api(`/estimate?${fareParams}`).then(quote => {
                    if (card.isConnected) {
                        card.querySelector('.cab-price').textContent = `₹${quote.fare}${quote.approximate && !(pickupCoordinates && destinationCoordinates) ? '+' : ''}`;
                    }
                }).catch(() => { if (card.isConnected) card.querySelector('.cab-price').textContent = 'Fare unavailable'; });
            }
        });
        const selected = availableVehicles.find(cab => cab.vehicleType === selectedCabType);
        bookRideBtn.disabled = !selected || selected.registeredDrivers === 0
            || !pickupCoordinates || !destinationCoordinates || pickupLabelPending || destinationLabelPending;
        bookRideBtn.textContent = !pickupCoordinates || !destinationCoordinates
                || pickupLabelPending || destinationLabelPending
            ? 'Select pickup and destination locations'
            : selectedCabType ? `Book ${selectedCabType}` : 'Choose a vehicle';
    }

    // ================= 4. SCHEDULE FOR LATER MODAL =================
    const whenDropdown = document.getElementById('whenDropdown');
    const selectedTime = document.getElementById('selectedTime');
    const scheduleModal = document.getElementById('scheduleModal');
    const scheduleDate = document.getElementById('scheduleDate');
    const scheduleTime = document.getElementById('scheduleTime');

    function toLocalDateInputValue(date) {
        const year = date.getFullYear();
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
    }

    function toLocalTimeInputValue(date) {
        const hour = String(date.getHours()).padStart(2, '0');
        const minute = String(date.getMinutes()).padStart(2, '0');
        return `${hour}:${minute}`;
    }

    function updateScheduleTimeMinimum() {
        const minimum = new Date(Math.ceil((Date.now() + 10 * 60_000) / 60_000) * 60_000);
        scheduleTime.min = scheduleDate.value === toLocalDateInputValue(minimum)
            ? toLocalTimeInputValue(minimum) : '';
    }

    function openScheduleModal() {
        const now = new Date();
        const soon = new Date(Math.ceil((now.getTime() + 15 * 60_000) / 60_000) * 60_000);
        scheduleDate.min = toLocalDateInputValue(now);
        scheduleDate.value = toLocalDateInputValue(soon);
        scheduleTime.value = toLocalTimeInputValue(soon);
        updateScheduleTimeMinimum();
        scheduleModal.classList.add('open');
        scheduleDate.focus();
    }

    whenDropdown.addEventListener('click', (e) => { e.stopPropagation(); whenDropdown.classList.toggle('open'); });
    document.querySelectorAll('.dropdown-item').forEach(item => {
        item.addEventListener('click', (e) => {
            e.stopPropagation();
            if (item.getAttribute('data-value') === 'Schedule for later') {
                openScheduleModal();
            } else {
                selectedTime.textContent = item.getAttribute('data-value');
            }
            whenDropdown.classList.remove('open');
        });
    });

    scheduleDate.addEventListener('change', updateScheduleTimeMinimum);
    document.getElementById('cancelScheduleBtn').addEventListener('click', () => scheduleModal.classList.remove('open'));
    document.getElementById('confirmScheduleBtn').addEventListener('click', () => {
        if (!scheduleDate.reportValidity() || !scheduleTime.reportValidity()) return;
        if (scheduleDate.value < toLocalDateInputValue(new Date())) {
            showToast('Choose today or a future date.');
            scheduleDate.focus();
            return;
        }
        const [year, month, day] = scheduleDate.value.split('-').map(Number);
        const [hour, minute] = scheduleTime.value.split(':').map(Number);
        const departure = new Date(year, month - 1, day, hour, minute);
        if (departure.getTime() <= Date.now()) {
            showToast('Choose a departure time in the future. At least 15 minutes is recommended.');
            scheduleTime.focus();
            return;
        }
        selectedScheduledAt = departure.toISOString();
        selectedTime.textContent = departure.toLocaleString(undefined, {
            dateStyle: 'medium',
            timeStyle: 'short'
        });
        scheduleModal.classList.remove('open');
    });
    document.querySelectorAll('#timeMenu .dropdown-item').forEach(item => {
        if (item.dataset.value === 'Leave Now') {
            item.addEventListener('click', () => {
                selectedScheduledAt = null;
                selectedTime.textContent = 'Leave Now';
            });
        }
    });

    // ================= 5. LIVE TRIP DISPATCH & COMPLETION =================
    const homeView = document.getElementById('homeView');
    const assigningView = document.getElementById('assigningView');
    const activeRideView = document.getElementById('activeRideView');

    bookRideBtn.addEventListener('click', async () => {
        if (!pickupInput.value.trim() || !dropInput.value.trim() || !selectedCabType) {
            showToast('Add your pickup and destination, then choose a vehicle.');
            return;
        }
        if (!pickupCoordinates || !destinationCoordinates) {
            showToast('Choose both locations from the address suggestions or enter valid addresses.');
            return;
        }
        setBusy(bookRideBtn, true, 'Booking ride...');
        document.getElementById('cancelSearchBtn').hidden = false;
        document.getElementById('retryRideBtn').hidden = true;
        requestNotificationPermission();
        try {
            currentRide = await api('/book-ride', { method: 'POST', body: JSON.stringify({
                pickup: pickupInput.value.trim(), destination: dropInput.value.trim(), vehicleType: selectedCabType,
                scheduledFor: selectedScheduledAt, pickupLatitude: pickupCoordinates?.latitude ?? null,
                pickupLongitude: pickupCoordinates?.longitude ?? null,
                dropoffLatitude: destinationCoordinates?.latitude ?? null,
                dropoffLongitude: destinationCoordinates?.longitude ?? null
            }) });
        } catch (error) {
            showToast(error.message);
            setBusy(bookRideBtn, false);
            return;
        }
        setBusy(bookRideBtn, false);
        homeView.classList.remove('active');
        assigningView.classList.add('active');
        updateAssignmentMessage(currentRide);
        startRidePolling();
    });

    function updateAssignmentMessage(ride) {
        const heading = document.querySelector('#assigningView h3');
        const message = document.querySelector('#assigningView p');
        if (ride.scheduledFor && Date.parse(ride.scheduledFor) > Date.now()) {
            heading.textContent = 'Scheduled ride request saved';
            message.textContent = 'Driver assignment will begin closer to your departure time.';
        } else if (ride.driverAvailable) {
            heading.textContent = 'Finding an available driver...';
            message.textContent = 'A matching driver is online. Your ride is not assigned until a driver accepts.';
        } else {
            heading.textContent = 'Driver not available';
            message.textContent = 'No eligible drivers are online. Drivers must open the secure HTTPS site and allow location to receive ride requests.';
        }
    }

    async function cancelCurrentRide() {
        if (!currentRide) return;
        try {
            currentRide = await api(`/rides/${currentRide.id}/status?status=CANCELLED`, { method: 'PATCH' });
            window.clearInterval(ridePollTimer);
            assigningView.classList.remove('active');
            activeRideView.classList.remove('active');
            homeView.classList.add('active');
            currentRide = null;
            showToast('Ride cancelled.');
        } catch (error) { showToast(error.message); }
    }

    document.getElementById('cancelSearchBtn').addEventListener('click', cancelCurrentRide);
    document.getElementById('cancelRideBtn').addEventListener('click', cancelCurrentRide);
    document.getElementById('retryRideBtn').addEventListener('click', () => {
        if (!currentRide) return;
        pickupInput.value = currentRide.pickup;
        dropInput.value = currentRide.destination;
        pickupCoordinates = currentRide.pickupLatitude == null ? null
            : { latitude: currentRide.pickupLatitude, longitude: currentRide.pickupLongitude };
        destinationCoordinates = currentRide.dropoffLatitude == null ? null
            : { latitude: currentRide.dropoffLatitude, longitude: currentRide.dropoffLongitude };
        selectedCabType = null;
        currentRide = null;
        assigningView.classList.remove('active');
        homeView.classList.add('active');
        loadVehicles();
        showToast('Choose an available vehicle and try again.');
    });

    function startRidePolling() {
        window.clearInterval(ridePollTimer);
        const update = async () => {
            if (!currentRide) return;
            try {
                const rides = await api('/rides');
                const latest = rides.find(ride => ride.id === currentRide.id);
                if (!latest) return;
                currentRide = latest;
                await pollNotifications();
                if (latest.status === 'REQUESTED') {
                    updateAssignmentMessage(latest);
                } else if (latest.status === 'NO_DRIVER_AVAILABLE') {
                    document.querySelector('#assigningView h3').textContent = 'Driver not available';
                    document.querySelector('#assigningView p').textContent =
                        'No eligible drivers are online. Drivers must open the secure HTTPS site and allow location to receive ride requests.';
                    document.getElementById('cancelSearchBtn').hidden = true;
                    document.getElementById('retryRideBtn').hidden = false;
                } else if (latest.status === 'ASSIGNED' || latest.status === 'IN_PROGRESS') {
                    assigningView.classList.remove('active');
                    activeRideView.classList.add('active');
                    activeRideView.classList.toggle('ride-assigned', latest.status === 'ASSIGNED');
                    activeRideView.classList.toggle('ride-in-progress', latest.status === 'IN_PROGRESS');
                    document.getElementById('activeRideOtp').textContent = latest.otp;
                    document.getElementById('activeFareText').textContent = `₹${latest.fare}`;
                    document.getElementById('activeDriverName').textContent = latest.driverName || 'Driver assigned';
                    document.getElementById('activeCarInfo').textContent = latest.driverVehicle || latest.vehicleType;
                    updateRideStatus(latest);
                    renderActiveRideMap(latest);
                    document.getElementById('cancelRideBtn').hidden = latest.status === 'IN_PROGRESS';
                } else if (latest.status === 'COMPLETED') {
                    window.clearInterval(ridePollTimer);
                    activeRideView.classList.add('active');
                    activeRideView.classList.remove('ride-assigned');
                    activeRideView.classList.remove('ride-in-progress');
                    clearActiveRideMap();
                    document.querySelector('#activeRideView .active-ride-card').hidden = true;
                    document.getElementById('rideCompletedDetails').textContent =
                        `Final fare ₹${latest.fare} · Route distance ${latest.distanceKm} km · ${latest.vehicleType} · ${latest.pickup} to ${latest.destination}${latest.durationMinutes ? ` · about ${latest.durationMinutes} min by road` : ''}`;
                    document.getElementById('rideCompletedBanner').hidden = false;
                    currentRide = null;
                    window.setTimeout(async () => {
                        document.getElementById('rideCompletedBanner').hidden = true;
                        document.querySelector('#activeRideView .active-ride-card').hidden = false;
                        activeRideView.classList.remove('active');
                        await loadRidesHistory();
                        document.getElementById('ridesView').classList.add('active');
                    }, 3000);
                } else if (latest.status === 'CANCELLED') {
                    window.clearInterval(ridePollTimer);
                    assigningView.classList.remove('active');
                    activeRideView.classList.remove('active');
                    activeRideView.classList.remove('ride-assigned', 'ride-in-progress');
                    clearActiveRideMap();
                    homeView.classList.add('active');
                    currentRide = null;
                    showToast('This ride was cancelled.');
                }
            } catch (error) {
                    if (error.status === 401 || error.status === 403) {
                        window.clearInterval(ridePollTimer);
                        showToast('Your session expired or no longer has ride access. Sign in again.');
                        return;
                    }
                    if (Date.now() - lastRidePollingErrorAt >= 30_000) {
                        showToast(`${error.message} Retrying ride status automatically.`);
                        lastRidePollingErrorAt = Date.now();
                    }
                }
            };
        update();
        ridePollTimer = window.setInterval(update, 3000);
    }

    async function loadRidesHistory() {
        try {
            ridesHistoryData = await api('/rides');
        } catch (error) {
            ridesHistoryData = [];
            showToast(error.message);
        }
        renderRidesHistory();
    }

    function renderRidesHistory() {
        const historyListEl = document.getElementById('ridesHistoryList');
        historyListEl.replaceChildren();
        if (ridesHistoryData.length === 0) {
            const empty = document.createElement('p');
            empty.className = 'empty-state';
            empty.textContent = 'No rides have been booked yet.';
            historyListEl.appendChild(empty);
            return;
        }
        ridesHistoryData.forEach(ride => {
            const item = document.createElement('article');
            item.className = 'incoming-ride-card';
            const title = document.createElement('div');
            title.className = 'ir-top';
            const type = document.createElement('strong');
            type.textContent = ride.vehicleType;
            const fare = document.createElement('span');
            fare.className = 'fare-tag';
            fare.textContent = `₹${ride.fare}`;
            title.append(type, fare);
            const route = document.createElement('p');
            route.className = 'ride-route-copy';
            route.textContent = `${ride.pickup} → ${ride.destination}`;
            const status = document.createElement('small');
            status.className = 'ride-status-copy';
            status.textContent = `${ride.status.replace('_', ' ')} · ${new Date(ride.createdAt).toLocaleString()}`;
            item.append(title, route, status);
            if (ride.status === 'COMPLETED' || ride.status === 'CANCELLED') {
                const report = document.createElement('button');
                report.type = 'button';
                report.className = 'btn-outline';
                report.textContent = 'Report an issue';
                report.addEventListener('click', () => {
                    complaintRideId = ride.id;
                    document.getElementById('complaintForm').reset();
                    document.getElementById('complaintModal').classList.add('open');
                    document.getElementById('complaintCategory').focus();
                });
                item.appendChild(report);
            }
            historyListEl.appendChild(item);
        });
    }

    document.getElementById('cancelComplaintBtn').addEventListener('click', () => {
        document.getElementById('complaintModal').classList.remove('open');
        complaintRideId = null;
    });
    document.getElementById('complaintForm').addEventListener('submit', async event => {
        event.preventDefault();
        if (!complaintRideId) return;
        const button = event.currentTarget.querySelector('[type="submit"]');
        setBusy(button, true, 'Sending...');
        try {
            await api('/complaints', {
                method: 'POST',
                body: JSON.stringify({
                    rideId: complaintRideId,
                    category: document.getElementById('complaintCategory').value,
                    details: document.getElementById('complaintDetails').value.trim()
                })
            });
            document.getElementById('complaintModal').classList.remove('open');
            complaintRideId = null;
            showToast('Your report was saved securely.');
        } catch (error) {
            showToast(error.message);
        } finally {
            setBusy(button, false);
        }
    });

    // ================= 6. DARK MODE TOGGLE =================
    document.getElementById('darkModeToggle').addEventListener('change', (e) => {
        if(e.target.checked) {
            document.getElementById('appContainer').classList.add('dark-mode');
        } else {
            document.getElementById('appContainer').classList.remove('dark-mode');
        }
        localStorage.setItem('mithila-dark-mode', e.target.checked ? 'true' : 'false');
    });

    const darkModeEnabled = localStorage.getItem('mithila-dark-mode') === 'true';
    document.getElementById('darkModeToggle').checked = darkModeEnabled;
    document.getElementById('appContainer').classList.toggle('dark-mode', darkModeEnabled);

    // Location FAB quick trigger
    api('/me').then(async user => {
        if (!user) return;
        applyUser(user);
        roleSelectView.classList.remove('active');
        openSelectedPortal();
        if (user.role !== 'RIDER') return;
        const rides = await api('/rides');
        currentRide = rides.find(ride => ['REQUESTED', 'ASSIGNED', 'IN_PROGRESS', 'NO_DRIVER_AVAILABLE'].includes(ride.status)) || null;
        if (!currentRide) return;
        if (currentRide.status === 'REQUESTED' || currentRide.status === 'NO_DRIVER_AVAILABLE') {
            homeView.classList.remove('active');
            assigningView.classList.add('active');
            if (currentRide.status === 'NO_DRIVER_AVAILABLE') {
                document.querySelector('#assigningView h3').textContent = 'Driver not available';
                document.querySelector('#assigningView p').textContent = 'Try again or choose another vehicle.';
                document.getElementById('cancelSearchBtn').hidden = true;
                document.getElementById('retryRideBtn').hidden = false;
            }
        } else {
            activeRideView.classList.add('active');
        }
        startRidePolling();
    }).catch(error => showToast(error.message));
});
