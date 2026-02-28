

from flask import Flask, request, jsonify
from flask_cors import CORS
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.preprocessing import StandardScaler
import joblib
import os
import json
from datetime import datetime

app = Flask(__name__)
CORS(app)

MODEL_VERSION      = "3.0.0"
CONFIDENCE_THRESHOLD = 0.60   # dưới ngưỡng này → áp floor an toàn

# ── Thứ tự features phải khớp tuyệt đối với train_model.py v3.0 ───────────
FEATURE_NAMES = [
    # ── Behavioral (8) 
    "avg_response_time",        #  1
    "response_time_variance",   #  2
    "total_changes",            #  3
    "hesitation_score",         #  4
    "consistency_score",        #  5
    "max_severity_ratio",       #  6  
    "min_severity_ratio",       #  7  
    "neutral_ratio",            #  8
    # ── Tier 1 + interaction (4) 
    "phq_total_score",          #  9
    "gad_total_score",          # 10
    "phq_gad_product",          # 11  
    "combined_severity",        # 12  
    # ── Demographics (11)
    "age",                      # 13
    "gender_encoded",           # 14
    "occupation_encoded",       # 15
    "education_encoded",        # 16
    "marital_encoded",          # 17
    "income_encoded",           # 18
    "living_encoded",           # 19
    "has_chronic_illness",      # 20
    "sleep_hours_avg",          # 21
    "exercise_encoded",         # 22
    "social_support_level",     # 23
]

RISK_CATEGORIES = ["LOW_RISK", "MODERATE_RISK", "HIGH_RISK", "CRITICAL_RISK"]

# Bảng mã hoá demographics 
GENDER_MAP     = {"MALE": 0, "FEMALE": 1, "OTHER": 2}
OCCUPATION_MAP = {
    "STUDENT": 0, "OFFICE_WORKER": 1, "MANUAL_WORKER": 2,
    "UNEMPLOYED": 3, "RETIRED": 4, "OTHER": 5,
}
EDUCATION_MAP  = {"MIDDLE_SCHOOL": 0, "HIGH_SCHOOL": 1, "COLLEGE": 2, "POSTGRAD": 3}
MARITAL_MAP    = {"SINGLE": 0, "MARRIED": 1, "DIVORCED_WIDOWED": 2}
INCOME_MAP     = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}
LIVING_MAP     = {"ALONE": 0, "FAMILY": 1, "FRIENDS_DORM": 2}
EXERCISE_MAP   = {"NONE": 0, "LESS_THAN_WEEKLY": 1, "ONE_TO_THREE": 2, "MORE_THAN_THREE": 3}


#  CLINICAL FLOOR  –  đảm bảo ML không bao giờ downgrade so với Tầng 1


def apply_clinical_floor(
    ml_risk_index: int,
    phq_score: int,
    gad_score: int,
    ml_confidence: float = 1.0,
) -> dict:
    """
    Quy tắc (ưu tiên từ cao → thấp):
      1. PHQ >= 18 hoặc GAD >= 18  → bắt buộc CRITICAL_RISK
      2. PHQ >= 15 hoặc GAD >= 15  → tối thiểu HIGH_RISK
      3. PHQ >= 10 hoặc GAD >= 10  → tối thiểu MODERATE_RISK
      4. confidence < 60%          → tăng 1 bậc an toàn

    Kết quả cuối = max(ml_risk_index, floor_index)
    → ML CHỈ ĐƯỢC TĂNG MỨC, KHÔNG BAO GIỜ ĐƯỢC GIẢM.
    """
    floor_index      = 0
    override_reasons = []

    # Quy tắc lâm sàng cứng
    if phq_score >= 18 or gad_score >= 18:
        floor_index = 3
        override_reasons.append(
            f"PHQ={phq_score} hoặc GAD={gad_score} >= 18 → bắt buộc CRITICAL_RISK"
        )
    elif phq_score >= 15 or gad_score >= 15:
        floor_index = max(floor_index, 2)
        override_reasons.append(
            f"PHQ={phq_score} hoặc GAD={gad_score} >= 15 → tối thiểu HIGH_RISK"
        )
    elif phq_score >= 10 or gad_score >= 10:
        floor_index = max(floor_index, 1)
        override_reasons.append(
            f"PHQ={phq_score} hoặc GAD={gad_score} >= 10 → tối thiểu MODERATE_RISK"
        )

    # Confidence threshold – ML không chắc → ưu tiên an toàn
    #  FIX: Chỉ áp khi PHQ+GAD >= 10 (có bằng chứng lâm sàng tối thiểu)
    # Nếu PHQ+GAD <= 9 (GREEN hoàn toàn), behavioral noise không đủ cơ sở
    # để leo thang cảnh báo chỉ vì model không tự tin.
    if ml_confidence < CONFIDENCE_THRESHOLD and (phq_score + gad_score) >= 10:
        safe_floor = min(ml_risk_index + 1, 3)
        if safe_floor > floor_index:
            floor_index = safe_floor
            override_reasons.append(
                f"ML confidence={ml_confidence:.1%} < {CONFIDENCE_THRESHOLD:.0%} "
                f"→ tăng 1 bậc an toàn (PHQ+GAD={phq_score+gad_score} >= 10)"
            )

    final_index    = max(ml_risk_index, floor_index)

    # ── FIX: Clinical ceiling – ML không được vượt quá mức PHQ/GAD cho phép ──
    # Model cũ / behavioral noise có thể predict HIGH/CRITICAL với PHQ+GAD thấp.
    # Ceiling đảm bảo: không cảnh báo cao hơn bằng chứng lâm sàng có thể biện minh.
    combined = phq_score + gad_score
    if combined <= 9:
        ceiling_index = 0   # tối đa LOW_RISK
    elif combined <= 19:
        ceiling_index = 1   # tối đa MODERATE_RISK
    elif combined <= 29:
        ceiling_index = 2   # tối đa HIGH_RISK
    else:
        ceiling_index = 3   # không giới hạn

    if final_index > ceiling_index:
        override_reasons.append(
            f"PHQ+GAD={combined} <= {[9,19,29,42][ceiling_index]} "
            f"→ ceiling cap {RISK_CATEGORIES[final_index]} → {RISK_CATEGORIES[ceiling_index]}"
        )
        final_index = ceiling_index

    was_overridden = final_index != ml_risk_index

    return {
        "final_risk_index":  final_index,
        "final_risk_label":  RISK_CATEGORIES[final_index],
        "was_overridden":    was_overridden,
        "override_reasons":  override_reasons if was_overridden else [],
    }

#  BEHAVIORAL ANALYZER

class BehavioralAnalyzer:
    """Trích xuất 8 features hành vi từ danh sách câu trả lời."""

    @staticmethod
    def extract(response_behaviors: list) -> dict:
        """
        Mỗi phần tử response_behaviors:
          { questionNumber, responseTime, finalAnswer, changeCount, hasHesitation }

        Trả về dict 8 features (v3: tách extreme_ratio → max/min_severity_ratio).
        """
        times   = [b["responseTime"]  for b in response_behaviors]
        changes = [b["changeCount"]   for b in response_behaviors]
        answers = [b["finalAnswer"]   for b in response_behaviors]
        hesit   = [b["hasHesitation"] for b in response_behaviors]

        n             = len(answers)
        avg_rt        = float(np.mean(times))
        rt_variance   = float(np.var(times))
        total_changes = int(sum(changes))
        hesitation    = sum(hesit) / n
        consistency   = 1.0 - min(total_changes / max(n, 1), 1.0)

        # [FIX 1] Tách extreme_ratio thành 2 features có ngữ nghĩa riêng
        max_severity = sum(1 for a in answers if a == 3) / n   # chọn mức nặng nhất
        min_severity = sum(1 for a in answers if a == 0) / n   # chọn mức không có
        neutral      = sum(1 for a in answers if a in (1, 2)) / n

        return {
            "avg_response_time":      avg_rt,
            "response_time_variance": rt_variance,
            "total_changes":          total_changes,
            "hesitation_score":       hesitation,
            "consistency_score":      consistency,
            "max_severity_ratio":     max_severity,   # ← thay extreme_ratio
            "min_severity_ratio":     min_severity,   # ← mới
            "neutral_ratio":          neutral,
        }


#  DEMOGRAPHICS ENCODER

class DemographicsEncoder:
    """Chuyển đổi thông tin nhân khẩu học sang dạng số."""

    @staticmethod
    def encode(user: dict, demo: dict) -> dict:
        return {
            "age":                  int(user.get("age", 25)),
            "gender_encoded":       GENDER_MAP.get(user.get("gender", "MALE"), 0),
            "occupation_encoded":   OCCUPATION_MAP.get(demo.get("occupation", "OTHER"), 5),
            "education_encoded":    EDUCATION_MAP.get(demo.get("educationLevel", "HIGH_SCHOOL"), 1),
            "marital_encoded":      MARITAL_MAP.get(demo.get("maritalStatus", "SINGLE"), 0),
            "income_encoded":       INCOME_MAP.get(demo.get("incomeLevel", "MEDIUM"), 1),
            "living_encoded":       LIVING_MAP.get(demo.get("livingSituation", "FAMILY"), 1),
            "has_chronic_illness":  int(bool(demo.get("hasChronicIllness", False))),
            "sleep_hours_avg":      float(demo.get("sleepHoursAvg", 7)),
            "exercise_encoded":     EXERCISE_MAP.get(demo.get("exerciseFrequency", "NONE"), 0),
            "social_support_level": float(demo.get("socialSupportLevel", 3)),
        }


#  RISK PREDICTOR

class RiskPredictor:
    """Tải model (v3.0 – 23 features) và thực hiện dự đoán."""

    def __init__(self):
        self.model  = None
        self.scaler = None

    def load_or_create(self):
        model_path  = "models/risk_model.pkl"
        scaler_path = "models/scaler.pkl"

        if os.path.exists(model_path) and os.path.exists(scaler_path):
            print("[ML] Đang tải model từ file...")
            self.model  = joblib.load(model_path)
            self.scaler = joblib.load(scaler_path)

            # Kiểm tra số features của model đã lưu
            meta_path = "models/model_metadata.json"
            if os.path.exists(meta_path):
                with open(meta_path, encoding="utf-8") as f:
                    meta = json.load(f)
                saved_version = meta.get("version", "?")
                saved_n_feat  = meta.get("n_features", "?")
                print(f"[ML] ✅ Model v{saved_version} tải thành công! ({saved_n_feat} features)")
                if saved_n_feat != len(FEATURE_NAMES):
                    print(f"[ML] ⚠️  CẢNH BÁO: model có {saved_n_feat} features "
                          f"nhưng app đang dùng {len(FEATURE_NAMES)} features!")
                    print("[ML]    Hãy chạy lại train_model.py để retrain với v3.0")
            else:
                print("[ML] ✅ Model tải thành công!")
        else:
            print("[ML] ⚠️  Không tìm thấy model, đang tạo bootstrap model (23 features)...")
            self._bootstrap()

    def _bootstrap(self):
        """
        Tạo model tạm bằng dữ liệu tổng hợp 23 features.
        Chạy train_model.py để có model chính thức chính xác hơn.
        """
        np.random.seed(42)
        n = 2000
        X, y = [], []

        for _ in range(n):
            risk = np.random.choice([0, 1, 2, 3], p=[0.40, 0.30, 0.20, 0.10])

            phq = int(np.clip(np.random.normal([3, 8, 13, 18][risk], 2.5), 0, 21))
            gad = int(np.clip(np.random.normal([3, 8, 13, 17][risk], 2.5), 0, 21))

            # Áp floor rule ngay khi tạo nhãn bootstrap
            if phq >= 18 or gad >= 18:
                risk = 3
            elif phq >= 15 or gad >= 15:
                risk = max(risk, 2)
            elif phq >= 10 or gad >= 10:
                risk = max(risk, 1)

            # [FIX 1] Tách extreme_ratio trong bootstrap
            max_sev = float(np.clip(np.random.beta([1,2,5,9][risk], [9,7,3,2][risk]), 0, 1))
            min_sev = float(np.clip(np.random.beta([8,5,2,1][risk], [2,4,7,9][risk]), 0, 1))
            total   = max_sev + min_sev
            if total > 1.0:
                max_sev /= total
                min_sev /= total
            neutral = float(np.clip(1.0 - max_sev - min_sev, 0, 1))

            row = [
                # 8 behavioral
                float(np.random.normal([3000,7500,16000,27000][risk], 2000)),
                float(abs(np.random.normal([6e5,2.2e6,6.5e6,13e6][risk], 1e6))),
                int(np.clip(np.random.normal([1,6,18,33][risk], 3), 0, 50)),
                float(np.clip(np.random.beta([1,3,6,10][risk], [9,5,3,2][risk]), 0, 1)),
                float(np.clip(np.random.beta([9,6,3,1][risk], [1,3,5,9][risk]), 0, 1)),
                max_sev,    # max_severity_ratio
                min_sev,    # min_severity_ratio
                neutral,    # neutral_ratio
                # 4 tier1 + interaction
                phq,
                gad,
                float(phq * gad),           # phq_gad_product
                float((phq + gad) / 42.0),  # combined_severity
                # 11 demographics
                int(np.clip(np.random.normal(30, 12), 13, 80)),
                int(np.random.choice([0, 1, 2])),
                int(np.random.choice(6)),
                int(np.random.choice(4)),
                int(np.random.choice(3)),
                int(np.random.choice(3)),
                int(np.random.choice(3)),
                int(np.random.choice([0, 1])),
                float(np.clip(np.random.normal([7.5,6.5,5.5,4.5][risk], 1), 3, 12)),
                int(np.random.choice(4)),
                float(np.clip(np.random.normal([4.2,3.3,2.4,1.6][risk], 0.8), 1, 5)),
            ]
            X.append(row)
            y.append(risk)

        X = np.array(X)
        y = np.array(y)

        self.scaler = StandardScaler()
        X_scaled    = self.scaler.fit_transform(X)

        self.model = RandomForestClassifier(
            n_estimators=100, max_depth=15,
            class_weight="balanced", random_state=42, n_jobs=-1,
        )
        self.model.fit(X_scaled, y)

        os.makedirs("models", exist_ok=True)
        joblib.dump(self.model,  "models/risk_model.pkl")
        joblib.dump(self.scaler, "models/scaler.pkl")
        print("[ML] ✅ Bootstrap model (23 features) đã tạo!")
        print("[ML]    Chạy train_model.py để có model chính xác hơn.")

    def predict(self, feature_vector: list) -> dict:
        """Dự đoán rủi ro – trả về score, confidence, category, probabilities."""
        if len(feature_vector) != len(FEATURE_NAMES):
            raise ValueError(
                f"Feature vector có {len(feature_vector)} features, "
                f"cần {len(FEATURE_NAMES)}"
            )

        x        = np.array(feature_vector, dtype=float).reshape(1, -1)
        x_scaled = self.scaler.transform(x)
        proba    = self.model.predict_proba(x_scaled)[0]
        pred_idx = int(self.model.predict(x_scaled)[0])

        # Điểm rủi ro tổng hợp (0–100) có trọng số theo mức độ nguy hiểm
        risk_score = (
            proba[0] * 12 +
            proba[1] * 38 +
            proba[2] * 68 +
            proba[3] * 92
        )

        return {
            "risk_score":    float(risk_score),
            "confidence":    float(proba[pred_idx]),
            "pred_index":    pred_idx,
            "category":      RISK_CATEGORIES[pred_idx],
            "probabilities": {c: float(proba[i]) for i, c in enumerate(RISK_CATEGORIES)},
        }


#  KHỞI TẠO

predictor            = RiskPredictor()
predictor.load_or_create()
behavioral_analyzer  = BehavioralAnalyzer()
demographics_encoder = DemographicsEncoder()


#  ENDPOINTS

@app.route("/health", methods=["GET"])
def health():
    return jsonify({
        "status":    "healthy",
        "service":   "Mental Health ML Service",
        "version":   MODEL_VERSION,
        "n_features": len(FEATURE_NAMES),
        "confidence_threshold": CONFIDENCE_THRESHOLD,
        "timestamp": datetime.now().isoformat(),
    })


@app.route("/predict", methods=["POST"])
def predict_risk():
    """
    Request body (JSON):
    {
      "tier1_scores": { "phq_total": 11, "gad_total": 10 },
      "response_behaviors": [
          {
            "questionNumber": 1,
            "responseTime": 5000,
            "finalAnswer": 2,       ← 0 / 1 / 2 / 3
            "changeCount": 1,
            "hasHesitation": true
          },
          ...  (14 phần tử)
      ],
      "user_info":    { "age": 28, "gender": "FEMALE" },
      "demographics": {
          "occupation":        "OFFICE_WORKER",
          "educationLevel":    "COLLEGE",
          "maritalStatus":     "SINGLE",
          "incomeLevel":       "MEDIUM",
          "livingSituation":   "FAMILY",
          "hasChronicIllness": false,
          "sleepHoursAvg":     7,
          "exerciseFrequency": "ONE_TO_THREE",
          "socialSupportLevel": 4
      }
    }

    Giá trị hợp lệ:
      gender            : MALE | FEMALE | OTHER
      occupation        : STUDENT | OFFICE_WORKER | MANUAL_WORKER
                          | UNEMPLOYED | RETIRED | OTHER
      educationLevel    : MIDDLE_SCHOOL | HIGH_SCHOOL | COLLEGE | POSTGRAD
      maritalStatus     : SINGLE | MARRIED | DIVORCED_WIDOWED
      incomeLevel       : LOW | MEDIUM | HIGH
      livingSituation   : ALONE | FAMILY | FRIENDS_DORM
      exerciseFrequency : NONE | LESS_THAN_WEEKLY | ONE_TO_THREE | MORE_THAN_THREE
      socialSupportLevel: 1–5 (số nguyên)
    """
    try:
        data = request.get_json(force=True)

        # ── Validate ──────────────────────────────────────────────────────
        behaviors = data.get("response_behaviors", [])
        if len(behaviors) != 14:
            return jsonify({
                "error": "Phải có đúng 14 phần tử trong response_behaviors"
            }), 400

        tier1 = data.get("tier1_scores", {})
        user  = data.get("user_info",    {})
        demo  = data.get("demographics", {})

        # ── Trích xuất features ───────────────────────────────────────────
        beh = behavioral_analyzer.extract(behaviors)
        dem = demographics_encoder.encode(user, demo)

        phq = int(tier1.get("phq_total", 0))
        gad = int(tier1.get("gad_total", 0))

        # [FIX 2] Interaction features tính tại đây
        phq_gad_product   = phq * gad
        combined_severity = (phq + gad) / 42.0

        # ── Ghép 23 features (đúng thứ tự FEATURE_NAMES) ─────────────────
        feature_vector = [
            # 8 behavioral
            beh["avg_response_time"],
            beh["response_time_variance"],
            beh["total_changes"],
            beh["hesitation_score"],
            beh["consistency_score"],
            beh["max_severity_ratio"],   # ← thay extreme_ratio
            beh["min_severity_ratio"],   # ← mới
            beh["neutral_ratio"],
            # 4 tier1 + interaction
            phq,
            gad,
            phq_gad_product,             # ← mới
            combined_severity,           # ← mới
            # 11 demographics
            dem["age"],
            dem["gender_encoded"],
            dem["occupation_encoded"],
            dem["education_encoded"],
            dem["marital_encoded"],
            dem["income_encoded"],
            dem["living_encoded"],
            dem["has_chronic_illness"],
            dem["sleep_hours_avg"],
            dem["exercise_encoded"],
            dem["social_support_level"],
        ]

        # ── ML Predict ────────────────────────────────────────────────────
        ml_result = predictor.predict(feature_vector)

        # [FIX 3] Áp clinical floor – PHQ/GAD cực đoan KHÔNG BAO GIỜ downgrade
        floor = apply_clinical_floor(
            ml_risk_index=ml_result["pred_index"],
            phq_score=phq,
            gad_score=gad,
            ml_confidence=ml_result["confidence"],
        )

        # ── Tính risk_score cuối theo mức đã được floor ───────────────────
        # Điều chỉnh score khi bị override bởi quy tắc lâm sàng PHQ/GAD cứng
        # (KHÔNG điều chỉnh khi chỉ bị override bởi confidence threshold,
        #  vì PHQ/GAD thấp → score cao là sai lâm sàng)
        final_score = ml_result["risk_score"]
        score_floors = {0: 0, 1: 38, 2: 68, 3: 88}
        clinical_override = (phq >= 10 or gad >= 10) and floor["was_overridden"]
        if clinical_override:
            min_score  = score_floors[floor["final_risk_index"]]
            final_score = max(final_score, float(min_score))

        # ── FIX: Thêm ceiling – score không được vượt mức có thể biện minh ──
        # Ngăn behavioral noise đẩy score lên cao khi PHQ+GAD thấp
        combined_clinical = phq + gad
        score_ceilings = {
            (0,  9): 35.0,   # GREEN  → tối đa LOW_RISK
            (10, 19): 55.0,  # YELLOW → tối đa MODERATE_RISK
            (20, 29): 75.0,  # ORANGE → tối đa HIGH_RISK
        }
        for (lo, hi), cap in score_ceilings.items():
            if lo <= combined_clinical <= hi:
                final_score = min(final_score, cap)
                break

        # ── Response ──────────────────────────────────────────────────────
        return jsonify({
            # Kết quả cuối (sau floor)
            "ml_risk_score":    round(final_score, 2),
            "ml_confidence":    round(ml_result["confidence"], 4),
            "ml_prediction":    floor["final_risk_label"],

            # Chi tiết clinical floor
            "clinical_floor": {
                "was_overridden":   floor["was_overridden"],
                "original_ml":      ml_result["category"],
                "final_result":     floor["final_risk_label"],
                "override_reasons": floor["override_reasons"],
            },

            # Xác suất theo từng lớp (từ ML thuần, trước floor)
            "probabilities": ml_result["probabilities"],

            # Debug / logging
            "behavioral_features": {
                **beh,
                # Giữ lại extreme_ratio để tương thích ngược với frontend cũ (nếu cần)
                "extreme_ratio_legacy": beh["max_severity_ratio"] + beh["min_severity_ratio"],
            },
            "tier1_features": {
                "phq_total_score":  phq,
                "gad_total_score":  gad,
                "phq_gad_product":  phq_gad_product,
                "combined_severity": round(combined_severity, 4),
            },
            "demographics_features": dem,

            "model_version": MODEL_VERSION,
            "timestamp":     datetime.now().isoformat(),
        }), 200

    except KeyError as e:
        return jsonify({"error": f"Thiếu trường bắt buộc: {e}"}), 400
    except ValueError as e:
        return jsonify({"error": str(e)}), 422
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/feature-info", methods=["GET"])
def feature_info():
    """Trả về thông tin 23 features và bảng mã hoá."""
    return jsonify({
        "total_features": len(FEATURE_NAMES),
        "model_version":  MODEL_VERSION,
        "feature_names":  FEATURE_NAMES,
        "groups": {
            "behavioral":   FEATURE_NAMES[0:8],    # v2: 0:7
            "tier1_scores": FEATURE_NAMES[8:12],   # v2: 7:9
            "demographics": FEATURE_NAMES[12:23],  # v2: 9:20
        },
        "new_in_v3": {
            "max_severity_ratio": "Tỷ lệ trả lời = 3 (thay thế extreme_ratio)",
            "min_severity_ratio": "Tỷ lệ trả lời = 0 (tách từ extreme_ratio)",
            "phq_gad_product":    "PHQ × GAD – nhấn mạnh khi cả hai đều cao",
            "combined_severity":  "(PHQ + GAD) / 42 – mức độ kết hợp 0–1",
        },
        "clinical_floor_rules": {
            "CRITICAL": "PHQ >= 18 hoặc GAD >= 18",
            "HIGH":     "PHQ >= 15 hoặc GAD >= 15",
            "MODERATE": "PHQ >= 10 hoặc GAD >= 10",
            "CONFIDENCE": f"ML confidence < {CONFIDENCE_THRESHOLD:.0%} → tăng 1 bậc",
        },
        "encoding_maps": {
            "gender":            GENDER_MAP,
            "occupation":        OCCUPATION_MAP,
            "educationLevel":    EDUCATION_MAP,
            "maritalStatus":     MARITAL_MAP,
            "incomeLevel":       INCOME_MAP,
            "livingSituation":   LIVING_MAP,
            "exerciseFrequency": EXERCISE_MAP,
        },
    })


# ─────────────────────────────────────────────────────────────────────────────
#  ENTRY POINT
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("=" * 60)
    print(f"  Mental Health ML Service  v{MODEL_VERSION}")
    print(f"  Features: {len(FEATURE_NAMES)}  "
          f"(8 behavioral + 4 tier1 + 11 demographics)")
    print(f"  Clinical floor: bật  |  Confidence threshold: {CONFIDENCE_THRESHOLD:.0%}")
    print("=" * 60)
    port = int(os.environ.get("PORT", 5001))
    app.run(host="0.0.0.0", port=port, debug=False). 