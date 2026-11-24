package cn.jualn.miniapp.module.person.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+20)
public class PersonNoStoreFilter extends OncePerRequestFilter {
    public static boolean resource(String path) {
        return path.equals("/v1/admin/identity-stages") || path.equals("/v1/admin/persons") || path.startsWith("/v1/admin/persons/")
                || path.equals("/v1/admin/organizations") || path.startsWith("/v1/admin/organizations/")
                || path.equals("/v1/admin/person-management-scopes") || path.startsWith("/v1/admin/person-management-scopes/")
                || path.equals("/v1/admin/person-identifier-namespaces") || path.equals("/v1/admin/person-command-receipts")
                || path.equals("/v1/admin/user-person-bindings") || path.startsWith("/v1/admin/user-person-bindings/")
                || path.equals("/v1/admin/user-person-binding-changes") || path.startsWith("/v1/admin/person-account-bindings/")
                || path.startsWith("/v1/admin/party-personnel/") || qualificationAdmin(path);
    }
    public static boolean qualificationAdmin(String path) {return path.equals("/v1/admin/party-roster-imports") || path.startsWith("/v1/admin/party-roster-imports/")
        || path.equals("/v1/admin/party-roster-entries") || path.startsWith("/v1/admin/party-roster-entries/")
        || path.equals("/v1/admin/mentors") || path.startsWith("/v1/admin/mentors/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(resource(path) || path.equals("/v1/users/me/person-binding") || path.equals("/v1/users/me/party-identity-applications") || path.equals("/v1/users/me/party-identity") || path.startsWith("/v1/mentors/")) response.setHeader("Cache-Control","no-store");
        chain.doFilter(request,response);
    }
}
