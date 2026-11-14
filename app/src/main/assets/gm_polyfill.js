(function() {
    if (window.__gmPolyfillLoaded) return;
    window.__gmPolyfillLoaded = true;

    const callbacks = {};
    let reqCounter = 0;

    window.__gmResolveXhr = function(reqId, isSuccess, data) {
        const cb = callbacks[reqId];
        if (!cb) return;
        delete callbacks[reqId];
        if (isSuccess && typeof cb.onload === 'function') {
            cb.onload(data);
        } else if (!isSuccess && typeof cb.onerror === 'function') {
            cb.onerror(data);
        }
    };

    window.GM_setValue = function(key, value) {
        try {
            window.AndroidGMBridge.setValue(String(key), JSON.stringify(value));
        } catch (e) {
            console.error("GM_setValue error:", e);
        }
    };

    window.GM_getValue = function(key, defaultValue) {
        try {
            const raw = window.AndroidGMBridge.getValue(String(key), "__GM_UNDEFINED__");
            if (raw === "__GM_UNDEFINED__" || raw === null) return defaultValue;
            return JSON.parse(raw);
        } catch (e) {
            return defaultValue;
        }
    };

    window.GM_deleteValue = function(key) {
        window.AndroidGMBridge.deleteValue(String(key));
    };

    window.GM_listValues = function() {
        try {
            return JSON.parse(window.AndroidGMBridge.listValues());
        } catch (e) {
            return [];
        }
    };

    window.GM_addStyle = function(css) {
        const inject = () => {
            const style = document.createElement('style');
            style.textContent = css;
            (document.head || document.documentElement).appendChild(style);
        };
        if (document.head || document.documentElement) {
            inject();
        } else {
            document.addEventListener('DOMContentLoaded', inject, { once: true });
        }
    };

    window.GM_xmlhttpRequest = function(details) {
        const reqId = "xhr_" + (++reqCounter) + "_" + Date.now();
        callbacks[reqId] = {
            onload: details.onload,
            onerror: details.onerror
        };
        const method = details.method || "GET";
        const url = details.url;
        const headers = details.headers ? JSON.stringify(details.headers) : "";
        const data = details.data ? String(details.data) : "";
        window.AndroidGMBridge.xmlHttpRequest(reqId, method, url, headers, data);
    };

    window.GM_log = function(...args) {
        window.AndroidGMBridge.log(args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' '));
    };

    window.GM = {
        setValue: async (k, v) => window.GM_setValue(k, v),
        getValue: async (k, d) => window.GM_getValue(k, d),
        deleteValue: async (k) => window.GM_deleteValue(k),
        listValues: async () => window.GM_listValues(),
        addStyle: (css) => window.GM_addStyle(css),
        xmlHttpRequest: (details) => window.GM_xmlhttpRequest(details)
    };

    // Optional PDA-style Promise wrappers for cross-origin GET/POST
    window.PDA_httpGet = function(url, headers = {}) {
        return new Promise((resolve, reject) => {
            window.GM_xmlhttpRequest({
                method: "GET",
                url: url,
                headers: headers,
                onload: (res) => resolve(res),
                onerror: (err) => reject(err)
            });
        });
    };

    window.PDA_httpPost = function(url, headers = {}, body = "") {
        return new Promise((resolve, reject) => {
            window.GM_xmlhttpRequest({
                method: "POST",
                url: url,
                headers: headers,
                data: body,
                onload: (res) => resolve(res),
                onerror: (err) => reject(err)
            });
        });
    };
})();
