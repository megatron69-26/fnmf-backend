package com.llmgateway.dto.mobile;

import java.util.List;

/**
 * Response gộp gồm cả News + AI_Analysis cho Mobile App của Mạnh lưu thẳng vào Room DB.
 * Mỗi object chứa đầy đủ thông tin bài báo + kết quả AI phân tích,
 * Android chỉ cần gọi 1 API duy nhất rồi insert vào 2 bảng Room.
 */
public class MobileNewsBundleResponse {

    private MobileNewsDto news;
    private MobileAiAnalysisDto aiAnalysis;

    public MobileNewsBundleResponse() {
    }

    public MobileNewsBundleResponse(MobileNewsDto news, MobileAiAnalysisDto aiAnalysis) {
        this.news = news;
        this.aiAnalysis = aiAnalysis;
    }

    public MobileNewsDto getNews() {
        return news;
    }

    public void setNews(MobileNewsDto news) {
        this.news = news;
    }

    public MobileAiAnalysisDto getAiAnalysis() {
        return aiAnalysis;
    }

    public void setAiAnalysis(MobileAiAnalysisDto aiAnalysis) {
        this.aiAnalysis = aiAnalysis;
    }
}
