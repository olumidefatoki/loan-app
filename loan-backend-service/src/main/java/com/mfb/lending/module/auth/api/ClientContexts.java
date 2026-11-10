package com.mfb.lending.module.auth.api;

import com.mfb.lending.module.auth.domain.ClientContext;
import jakarta.servlet.http.HttpServletRequest;

public final class ClientContexts {

    private ClientContexts() {
    }

    public static ClientContext from(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (ip != null && ip.length() > 64) {
            ip = ip.substring(0, 64);
        }
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > 512) {
            userAgent = userAgent.substring(0, 512);
        }
        return new ClientContext(ip, userAgent);
    }
}
