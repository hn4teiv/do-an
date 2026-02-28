// API Configuration
const API_BASE_URL = 'https://tamkhoe-backend.onrender.com/api';

// Behavior tracking data
const behaviorData = [];
let startTime = Date.now();
let currentQuestionIndex = 0;

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    // Check if user exists
    const userId = localStorage.getItem('userId');
    const userName = localStorage.getItem('userName');
    
    if (!userId) {
        alert('Vui lòng đăng ký trước khi làm bài test!');
        window.location.href = 'register.html';
        return;
    }
    
    // Display user name
    document.getElementById('userName').textContent = userName || 'bạn';
    
    // Initialize behavior tracking for each question
    initializeBehaviorTracking();
    
    // Update progress on selection
    document.querySelectorAll('input[type="radio"]').forEach(radio => {
        radio.addEventListener('change', updateProgress);
    });
    
    // Form submit
    document.getElementById('screeningForm').addEventListener('submit', handleSubmit);
});

/**
 * Initialize behavior tracking for 14 questions
 */
function initializeBehaviorTracking() {
    const questions = document.querySelectorAll('.question');
    
    questions.forEach((question, index) => {
        const questionNum = index + 1;
        
        // Initialize tracking data
        behaviorData[questionNum] = {
            questionNumber: questionNum,
            startTime: null,
            responseTime: 0,
            finalAnswer: null,
            changeCount: 0,
            previousAnswer: null,
            hasHesitation: false
        };
        
        // Track when user interacts with question
        const radios = question.querySelectorAll('input[type="radio"]');
        radios.forEach(radio => {
            radio.addEventListener('focus', () => {
                // Record start time when first interact
                if (!behaviorData[questionNum].startTime) {
                    behaviorData[questionNum].startTime = Date.now();
                }
            });
            
            radio.addEventListener('change', (e) => {
                trackAnswer(questionNum, parseInt(e.target.value));
            });
        });
    });
}

/**
 * Track answer changes for behavior analysis
 */
function trackAnswer(questionNum, answerValue) {
    const data = behaviorData[questionNum];
    
    // Calculate response time
    if (data.startTime) {
        data.responseTime = Date.now() - data.startTime;
    }
    
    // Track answer changes
    if (data.previousAnswer !== null && data.previousAnswer !== answerValue) {
        data.changeCount++;
        data.hasHesitation = true;
    }
    
    data.previousAnswer = answerValue;
    data.finalAnswer = answerValue;
}

/**
 * Update progress bar
 */
function updateProgress() {
    const total = 14;
    const answered = getAnsweredCount();
    
    const percentage = (answered / total) * 100;
    document.getElementById('progressFill').style.width = percentage + '%';
    document.getElementById('currentQuestion').textContent = answered;
    
    // Enable submit button when all answered
    document.getElementById('submitBtn').disabled = answered < total;
}

/**
 * Get number of answered questions
 */
function getAnsweredCount() {
    let count = 0;
    
    // Check PHQ-7 (7 questions)
    for (let i = 1; i <= 7; i++) {
        if (document.querySelector(`input[name="phqQ${i}"]:checked`)) {
            count++;
        }
    }
    
    // Check GAD-7 (7 questions)
    for (let i = 1; i <= 7; i++) {
        if (document.querySelector(`input[name="gadQ${i}"]:checked`)) {
            count++;
        }
    }
    
    return count;
}

/**
 * Handle form submission
 */
async function handleSubmit(e) {
    e.preventDefault();
    
    // Validate all questions answered
    if (getAnsweredCount() < 14) {
        showError('Vui lòng trả lời tất cả 14 câu hỏi!');
        return;
    }
    
    // Collect form data
    const formData = collectFormData();
    
    // Store behavior data for Tier 2
    localStorage.setItem('behaviorData', JSON.stringify(behaviorData));
    
    // Show loading
    const submitBtn = document.getElementById('submitBtn');
    const originalText = submitBtn.textContent;
    submitBtn.textContent = 'Đang xử lý...';
    submitBtn.disabled = true;
    
    try {
        // Call Tier 1 API
        const response = await fetch(`${API_BASE_URL}/screening/tier1`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(formData)
        });
        
        const result = await response.json();
        
        if (response.ok) {
            // Save Tier 1 result
            localStorage.setItem('tier1Result', JSON.stringify(result));
            
            // Check if needs Tier 2
            if (result.requiresTier2Screening) {
                // Redirect to Tier 2
                window.location.href = 'screening-tier2.html';
            } else {
                // Go directly to results
                window.location.href = 'results.html';
            }
        } else {
            showError(result.message || 'Có lỗi xảy ra khi xử lý kết quả');
        }
    } catch (error) {
        console.error('Error:', error);
        showError('Không thể kết nối đến server. Vui lòng kiểm tra server đã chạy chưa.');
    } finally {
        submitBtn.textContent = originalText;
        submitBtn.disabled = false;
    }
}

/**
 * Collect form data
 */
function collectFormData() {
    const userId = parseInt(localStorage.getItem('userId'));
    
    return {
        userId: userId,
        // PHQ-7 questions
        phqQ1: parseInt(document.querySelector('input[name="phqQ1"]:checked').value),
        phqQ2: parseInt(document.querySelector('input[name="phqQ2"]:checked').value),
        phqQ3: parseInt(document.querySelector('input[name="phqQ3"]:checked').value),
        phqQ4: parseInt(document.querySelector('input[name="phqQ4"]:checked').value),
        phqQ5: parseInt(document.querySelector('input[name="phqQ5"]:checked').value),
        phqQ6: parseInt(document.querySelector('input[name="phqQ6"]:checked').value),
        phqQ7: parseInt(document.querySelector('input[name="phqQ7"]:checked').value),
        // GAD-7 questions
        gadQ1: parseInt(document.querySelector('input[name="gadQ1"]:checked').value),
        gadQ2: parseInt(document.querySelector('input[name="gadQ2"]:checked').value),
        gadQ3: parseInt(document.querySelector('input[name="gadQ3"]:checked').value),
        gadQ4: parseInt(document.querySelector('input[name="gadQ4"]:checked').value),
        gadQ5: parseInt(document.querySelector('input[name="gadQ5"]:checked').value),
        gadQ6: parseInt(document.querySelector('input[name="gadQ6"]:checked').value),
        gadQ7: parseInt(document.querySelector('input[name="gadQ7"]:checked').value),
        notes: `Completed at ${new Date().toISOString()}`
    };
}

/**
 * Show error message
 */
function showError(message) {
    const errorDiv = document.getElementById('error');
    errorDiv.textContent = message;
    errorDiv.style.display = 'block';
    
    // Scroll to error
    errorDiv.scrollIntoView({ behavior: 'smooth', block: 'center' });
    
    // Hide after 5 seconds
    setTimeout(() => {
        errorDiv.style.display = 'none';
    }, 5000);
}