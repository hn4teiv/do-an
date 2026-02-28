package com.example.spbn3.service;

import com.example.spbn3.dto.Tier1ScreeningRequest;
import com.example.spbn3.entity.ScreeningTier1;
import com.example.spbn3.entity.User;
import com.example.spbn3.repository.ScreeningTier1Repository;
import com.example.spbn3.repository.ScreeningTier2Repository;
import com.example.spbn3.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Tier1ScreeningService {
    
    private final ScreeningTier1Repository screeningTier1Repository;
    private final ScreeningTier2Repository screeningTier2Repository;
    private final UserRepository userRepository;
    
    // Constructor thủ công
    @Autowired
    public Tier1ScreeningService(
            ScreeningTier1Repository screeningTier1Repository,
            ScreeningTier2Repository screeningTier2Repository,
            UserRepository userRepository) {
        this.screeningTier1Repository = screeningTier1Repository;
        this.screeningTier2Repository = screeningTier2Repository;
        this.userRepository = userRepository;
    }
    
    @Transactional
    public ScreeningTier1 conductScreening(Tier1ScreeningRequest request) {
        // Tìm user
        User user = userRepository.findById(request.getUserId())
            .orElseThrow(() -> new RuntimeException("User không tồn tại với ID: " + request.getUserId()));
        
        // Tạo screening entity
        ScreeningTier1 screening = new ScreeningTier1();
        screening.setUser(user);
        
        // Lưu PHQ-7 answers
        screening.setPhqQ1(request.getPhqQ1());
        screening.setPhqQ2(request.getPhqQ2());
        screening.setPhqQ3(request.getPhqQ3());
        screening.setPhqQ4(request.getPhqQ4());
        screening.setPhqQ5(request.getPhqQ5());
        screening.setPhqQ6(request.getPhqQ6());
        screening.setPhqQ7(request.getPhqQ7());
        
        // Tính PHQ-7 total score
        int phqTotal = request.getPhqQ1() + request.getPhqQ2() + request.getPhqQ3() + 
                       request.getPhqQ4() + request.getPhqQ5() + request.getPhqQ6() + 
                       request.getPhqQ7();
        screening.setPhqTotalScore(phqTotal);
        screening.setPhqSeverity(ScreeningTier1.DepressionSeverity.fromScore(phqTotal));
        
        // Lưu GAD-7 answers
        screening.setGadQ1(request.getGadQ1());
        screening.setGadQ2(request.getGadQ2());
        screening.setGadQ3(request.getGadQ3());
        screening.setGadQ4(request.getGadQ4());
        screening.setGadQ5(request.getGadQ5());
        screening.setGadQ6(request.getGadQ6());
        screening.setGadQ7(request.getGadQ7());
        
        // Tính GAD-7 total score
        int gadTotal = request.getGadQ1() + request.getGadQ2() + request.getGadQ3() + 
                       request.getGadQ4() + request.getGadQ5() + request.getGadQ6() + 
                       request.getGadQ7();
        screening.setGadTotalScore(gadTotal);
        screening.setGadSeverity(ScreeningTier1.AnxietySeverity.fromScore(gadTotal));
        
        // Xác định symptom group
        screening.setSymptomGroup(determineSymptomGroup(phqTotal, gadTotal));
        
        // Xác định alert level
        screening.setAlertLevel(determineAlertLevel(phqTotal, gadTotal));
        
        // Lưu notes nếu có
        screening.setNotes(request.getNotes());
        
        return screeningTier1Repository.save(screening);
    }
    
    public String generateRecommendation(ScreeningTier1 screening) {
        StringBuilder recommendation = new StringBuilder();
        
        switch (screening.getAlertLevel()) {
            case GREEN:
                recommendation.append("Tình trạng sức khỏe tâm thần của bạn ở mức ổn định. ");
                recommendation.append("Hãy tiếp tục duy trì lối sống lành mạnh và chăm sóc bản thân.");
                break;
                
            case YELLOW:
                recommendation.append("Bạn đang có một số triệu chứng nhẹ. ");
                recommendation.append("Khuyến nghị: Theo dõi tình trạng, ");
                recommendation.append("thực hành kỹ thuật thư giãn, tập thể dục đều đặn, ");
                recommendation.append("và cân nhắc tái sàng lọc sau 2-4 tuần.");
                break;
                
            case ORANGE:
                recommendation.append("Bạn đang có triệu chứng ở mức độ trung bình. ");
                recommendation.append("Khuyến nghị mạnh: Tìm kiếm tư vấn từ chuyên gia sức khỏe tâm thần. ");
                recommendation.append("Cân nhắc liệu pháp tâm lý hoặc hỗ trợ chuyên môn.");
                break;
                
            case RED:
                recommendation.append("Bạn đang có triệu chứng nghiêm trọng. ");
                recommendation.append("KHUYẾN NGHỊ KHẨN CẤP: Cần can thiệp chuyên môn ngay lập tức. ");
                recommendation.append("Vui lòng liên hệ với bác sĩ tâm thần hoặc đường dây hỗ trợ khủng hoảng.");
                break;
        }
        
        // Thêm khuyến nghị cụ thể theo symptom group
        switch (screening.getSymptomGroup()) {
            case ANXIETY_ONLY:
                recommendation.append(" Tập trung vào các kỹ thuật giảm căng thẳng và lo âu.");
                break;
            case DEPRESSION_ONLY:
                recommendation.append(" Tập trung vào các hoạt động nâng cao tâm trạng và năng lượng.");
                break;
            case MIXED:
                recommendation.append(" Cần có kế hoạch điều trị toàn diện cho cả lo âu và trầm cảm.");
                break;
        }
        
        return recommendation.toString();
    }
    
    public boolean requiresTier2Screening(Long tier1ScreeningId) {
        ScreeningTier1 screening = screeningTier1Repository.findById(tier1ScreeningId)
            .orElseThrow(() -> new RuntimeException("Screening không tồn tại"));
        
        // Cần Tier 2 nếu:
        // 1. Alert level là ORANGE hoặc RED
        // 2. Hoặc có triệu chứng MIXED với điểm số trung bình trở lên
        
        if (screening.getAlertLevel() == ScreeningTier1.AlertLevel.ORANGE ||
            screening.getAlertLevel() == ScreeningTier1.AlertLevel.RED) {
            return true;
        }
        
        if (screening.getSymptomGroup() == ScreeningTier1.SymptomGroup.MIXED &&
            (screening.getPhqTotalScore() >= 10 || screening.getGadTotalScore() >= 10)) {
            return true;
        }
        
        return false;
    }
    
    private ScreeningTier1.SymptomGroup determineSymptomGroup(int phqScore, int gadScore) {
        boolean hasDepression = phqScore >= 5;
        boolean hasAnxiety = gadScore >= 5;
        
        if (hasDepression && hasAnxiety) {
            return ScreeningTier1.SymptomGroup.MIXED;
        } else if (hasDepression) {
            return ScreeningTier1.SymptomGroup.DEPRESSION_ONLY;
        } else if (hasAnxiety) {
            return ScreeningTier1.SymptomGroup.ANXIETY_ONLY;
        } else {
            return ScreeningTier1.SymptomGroup.NONE;
        }
    }
    
    private ScreeningTier1.AlertLevel determineAlertLevel(int phqScore, int gadScore) {
        int maxScore = Math.max(phqScore, gadScore);
        int combinedScore = phqScore + gadScore;
        
        // RED: Điểm số cao (>= 15) ở một trong hai thang đo HOẶC tổng điểm >= 30
        if (maxScore >= 15 || combinedScore >= 30) {
            return ScreeningTier1.AlertLevel.RED;
        }
        
        // ORANGE: Điểm số trung bình-cao (10-14) ở một trong hai thang đo HOẶC tổng điểm 20-29
        if (maxScore >= 10 || combinedScore >= 20) {
            return ScreeningTier1.AlertLevel.ORANGE;
        }
        
        // YELLOW: Điểm số nhẹ (5-9) ở một trong hai thang đo HOẶC tổng điểm 10-19
        if (maxScore >= 5 || combinedScore >= 10) {
            return ScreeningTier1.AlertLevel.YELLOW;
        }
        
        // GREEN: Điểm số tối thiểu
        return ScreeningTier1.AlertLevel.GREEN;
    }
}
