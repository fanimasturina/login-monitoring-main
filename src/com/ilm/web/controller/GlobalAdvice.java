package com.ilm.web.controller;

import com.ilm.web.service.RealtimeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Values every page needs, and one place for error handling (no stack traces shown to users). */
@ControllerAdvice
public class GlobalAdvice {
    private static final Logger LOG = Logger.getLogger(GlobalAdvice.class.getName());
    private final RealtimeService realtime;

    public GlobalAdvice(RealtimeService realtime) { this.realtime = realtime; }

    @ModelAttribute
    public void common(Model model, Authentication auth, HttpServletRequest request) {
        String role = View.role(auth);
        model.addAttribute("me", auth == null ? null : auth.getName());
        model.addAttribute("role", role);
        model.addAttribute("isAnalyst", "ANALYST".equals(role));
        model.addAttribute("isAdmin", "ADMIN".equals(role));
        model.addAttribute("canUpload", "ANALYST".equals(role) || "ADMIN".equals(role));
        model.addAttribute("canAudit", "ADMIN".equals(role) || "AUDITOR".equals(role));
        model.addAttribute("masked", View.masked(auth));
        model.addAttribute("realtimeOn", realtime.enabled());
        model.addAttribute("nav", request.getRequestURI().substring(request.getContextPath().length()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(Model model) {
        model.addAttribute("title", "Page not found");
        model.addAttribute("message", "The page does not exist or was moved.");
        return "error";
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String tooLarge(Model model) {
        model.addAttribute("title", "File is too large");
        model.addAttribute("message", "The maximum upload size is 25 MB. Split the file and upload the parts one by one.");
        return "error";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String failure(Exception e, Model model, HttpServletRequest request) {
        String ref = Long.toHexString(System.currentTimeMillis());
        LOG.log(Level.SEVERE, "Request failed [" + ref + "] " + request.getMethod() + " " + request.getRequestURI(), e);
        model.addAttribute("title", "Something went wrong");
        model.addAttribute("message", "The action could not be completed. Give this reference to the administrator: " + ref);
        return "error";
    }
}
