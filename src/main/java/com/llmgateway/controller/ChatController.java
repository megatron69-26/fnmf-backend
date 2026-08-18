package com.llmgateway.controller;

import com.llmgateway.dto.ChatRequest;
import com.llmgateway.dto.ChatResponse;
import com.llmgateway.service.LlmService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller cho LLM Gateway.
 * Nhận request từ client, forward sang LLM API qua LlmService.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final LlmService llmService;

    public ChatController(LlmService llmService) {
        this.llmService = llmService;
    }

    /**
     * POST /api/chat
     *
     * Nhận ChatRequest (message + optional model), gọi OpenAI, trả ChatResponse.
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatResponse response = llmService.chat(request.getModel(), request.getMessage());
        return ResponseEntity.ok(response);
    }
}
