package com.ilm.web.controller;

import com.ilm.web.config.AppProps;
import com.ilm.web.dao.EventDao;
import com.ilm.web.service.IngestService;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Upload of ATI exports and application logs (UC-04 in upload mode). */
@Controller
public class UploadController {
    private static final Logger LOG = Logger.getLogger(UploadController.class.getName());
    private final IngestService ingest;
    private final EventDao events;
    private final AppProps props;

    public UploadController(IngestService ingest, EventDao events, AppProps props) {
        this.ingest = ingest;
        this.events = events;
        this.props = props;
    }

    @GetMapping("/upload")
    public String form(@RequestParam(name = "errors", required = false) Long errorsOf, Model model) {
        model.addAttribute("batches", events.recentBatches(20));
        model.addAttribute("knownApps", Arrays.stream(props.get("apps.known", "").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        if (errorsOf != null) {
            model.addAttribute("errorsOf", errorsOf);
            model.addAttribute("errors", events.batchErrors(errorsOf, 100));
        }
        return "upload";
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file, @RequestParam(name = "appCode", required = false) String appCode,
            Authentication auth, RedirectAttributes ra) {
        if (file == null || file.isEmpty()) {
            ra.addFlashAttribute("err", "Choose a file first.");
            return "redirect:/upload";
        }
        String app = appCode == null ? "" : appCode.trim().toUpperCase();
        if (!app.isEmpty() && !app.matches("[A-Z0-9_\\-]{2,50}")) {
            ra.addFlashAttribute("err", "Application code may only contain letters, numbers, - and _ (for example APP-REGIS).");
            return "redirect:/upload";
        }
        try {
            Map<String, Object> result = ingest.ingestFile(file.getBytes(), file.getOriginalFilename(), app, auth.getName());
            ra.addFlashAttribute("result", result);
        } catch (IllegalArgumentException | IOException e) {
            LOG.log(Level.INFO, "Upload rejected: " + e.getMessage());
            ra.addFlashAttribute("err", "The file could not be read: " + e.getMessage());
        }
        return "redirect:/upload";
    }
}
