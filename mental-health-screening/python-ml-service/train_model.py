
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.pipeline import Pipeline
from sklearn.model_selection import train_test_split, cross_val_score, StratifiedKFold
from sklearn.preprocessing import StandardScaler
from sklearn.calibration import CalibratedClassifierCV
from sklearn.metrics import classification_report, confusion_matrix, accuracy_score
import joblib
import os
from datetime import datetime
import json




FEATURE_NAMES = [
    #  NHÓM 1: Hành vi trả lời (8 features) 
    "avg_response_time",        #  1. Thời gian trả lời trung bình (ms)
    "response_time_variance",   #  2. Độ biến thiên thời gian trả lời
    "total_changes",            #  3. Tổng số lần thay đổi câu trả lời
    "hesitation_score",         #  4. Tỷ lệ câu hỏi có do dự (0–1)
    "consistency_score",        #  5. Độ nhất quán câu trả lời (0–1)
  
    "max_severity_ratio",       #  6. Tỷ lệ trả lời = 3 (nặng nhất)   
    "min_severity_ratio",       #  7. Tỷ lệ trả lời = 0 (không có)    
    "neutral_ratio",            #  8. Tỷ lệ trả lời trung lập (1 hoặc 2)

    #  NHÓM 2: Điểm sàng lọc tầng 1 + interaction (4 features) 
    "phq_total_score",          #  9. Tổng điểm PHQ-7 (0–21)
    "gad_total_score",          # 10. Tổng điểm GAD-7 (0–21)
    "phq_gad_product",          # 11. PHQ × GAD  (nhấn mạnh khi cả hai đều cao)  
    "combined_severity",        # 12. (PHQ + GAD) / 42  – mức độ kết hợp (0–1)   

    #  NHÓM 3: Nhân khẩu học (11 features) 
    "age",                      # 13. Tuổi người dùng
    "gender_encoded",           # 14. Giới tính (0=Nam, 1=Nữ, 2=Khác)
    "occupation_encoded",       # 15. Nghề nghiệp (0=Học sinh/SV, 1=VP,
                                #                  2=Chân tay, 3=Thất nghiệp,
                                #                  4=Nghỉ hưu, 5=Khác)
    "education_encoded",        # 16. Trình độ (0=THCS, 1=THPT, 2=CĐ/ĐH, 3=Sau ĐH)
    "marital_encoded",          # 17. Hôn nhân (0=Độc thân, 1=Kết hôn, 2=Ly hôn/Góa)
    "income_encoded",           # 18. Thu nhập (0=Thấp, 1=TB, 2=Cao)
    "living_encoded",           # 19. Sống cùng ai (0=Một mình, 1=Gia đình, 2=Bạn bè)
    "has_chronic_illness",      # 20. Bệnh mãn tính (0=Không, 1=Có)
    "sleep_hours_avg",          # 21. Số giờ ngủ TB / đêm
    "exercise_encoded",         # 22. Tập thể dục (0=Không, 1=<1/tuần, 2=1-3/tuần, 3=>3/tuần)
    "social_support_level",     # 23. Hỗ trợ xã hội (1–5)
]

RISK_CATEGORIES = ["LOW_RISK", "MODERATE_RISK", "HIGH_RISK", "CRITICAL_RISK"]

# Ngưỡng độ tin cậy tối thiểu của ML; dưới ngưỡng → áp floor lâm sàng
CONFIDENCE_THRESHOLD = 0.60


#  SINH DỮ LIỆU TRAINING


class MentalHealthDataGenerator:
   

    def __init__(self, n_samples: int = 6000, random_seed: int = 42):
        self.n_samples = n_samples
        np.random.seed(random_seed)

    # Phân phối nhãn ban đầu trước floor rule
    RISK_PROBS = [0.40, 0.30, 0.20, 0.10]

    def generate_dataset(self) -> pd.DataFrame:
        """Sinh toàn bộ dataset và trả về DataFrame."""
        rows, labels = [], []
        for _ in range(self.n_samples):
            risk = np.random.choice([0, 1, 2, 3], p=self.RISK_PROBS)
            rows.append(self._sample(risk))
            labels.append(risk)

        df = pd.DataFrame(rows, columns=FEATURE_NAMES)
        df["risk_category"] = labels

        #  Quy tắc lâm sàng bắt buộc (áp sau khi sinh) 
        mask_moderate = (df["phq_total_score"] >= 10) | (df["gad_total_score"] >= 10)
        mask_high     = (df["phq_total_score"] >= 15) | (df["gad_total_score"] >= 15)
        mask_critical = (df["phq_total_score"] >= 18) | (df["gad_total_score"] >= 18)

        df.loc[mask_moderate, "risk_category"] = df.loc[mask_moderate, "risk_category"].clip(lower=1)
        df.loc[mask_high,     "risk_category"] = df.loc[mask_high,     "risk_category"].clip(lower=2)
        df.loc[mask_critical, "risk_category"] = df.loc[mask_critical, "risk_category"].clip(lower=3)

        return df

    @staticmethod
    def _clip(val, lo, hi):
        return float(np.clip(val, lo, hi))

    def _sample(self, risk: int) -> list:
        """Sinh 1 mẫu theo nhóm rủi ro (23 features)."""
        r = risk

        #  1. Behavioral 
        avg_rt        = self._clip(np.random.normal([3000, 7500, 16000, 27000][r],
                                                     [800, 1800, 3500, 6000][r]), 500, 60000)
        rt_variance   = self._clip(np.random.normal([600_000, 2_200_000, 6_500_000, 13_000_000][r],
                                                     [200_000, 700_000, 2_000_000, 4_000_000][r]), 0, 5e7)
        total_changes = int(np.clip(np.random.normal([1, 6, 18, 33][r], [1, 2, 4, 6][r]), 0, 50))
        hesitation    = self._clip(np.random.beta([1, 3, 6, 10][r], [9, 5, 3, 2][r]), 0, 1)
        consistency   = self._clip(np.random.beta([9, 6, 3, 1][r], [1, 3, 5, 9][r]), 0, 1)

        # : Tách extreme → max_severity và min_severity
        # max_severity: chọn mức 3 (nặng nhất) → tăng theo rủi ro
        max_sev = self._clip(np.random.beta([1, 2, 5, 9][r], [9, 7, 3, 2][r]), 0, 1)
        # min_severity: chọn mức 0 (không có) → giảm theo rủi ro
        min_sev = self._clip(np.random.beta([8, 5, 2, 1][r], [2, 4, 7, 9][r]), 0, 1)
        # Đảm bảo max + min <= 1
        total_ext = max_sev + min_sev
        if total_ext > 1.0:
            max_sev /= total_ext
            min_sev /= total_ext
        neutral_ratio = self._clip(1.0 - max_sev - min_sev + np.random.normal(0, 0.03), 0, 1)

        # ── 2. Tier 1 + interaction ───────────────────────────────────────
        phq_total = int(np.clip(np.random.normal([3, 8, 13, 18][r], 2.5), 0, 21))
        gad_total = int(np.clip(np.random.normal([3, 8, 13, 17][r], 2.5), 0, 21))

        #  Clamp behavioral features dựa trên PHQ/GAD thực tế
        # Tránh model học "hesitation cao + PHQ=2 → CRITICAL" từ dữ liệu nhiễu
        # Khi PHQ+GAD <= 9, hành vi cực đoan thường là quirk cá nhân, không phải bệnh lý
        combined_score = phq_total + gad_total
        if combined_score <= 9:
            # GREEN zone: giới hạn behavioral features ở mức thấp-trung
            hesitation  = min(hesitation,  0.35)
            total_changes = min(total_changes, 6)
            max_sev     = min(max_sev,     0.20)
        elif combined_score <= 19:
            # YELLOW zone: behavioral vừa phải
            hesitation  = min(hesitation,  0.60)
            total_changes = min(total_changes, 15)

        #  Interaction features
        phq_gad_product   = phq_total * gad_total           # 0–441
        combined_severity = (phq_total + gad_total) / 42.0  # 0–1

        #  3. Demographics 
        if r in (0, 1):
            age = int(np.clip(np.random.normal(35, 12), 13, 80))
        else:
            age = int(np.clip(np.random.choice(
                [np.random.normal(24, 6), np.random.normal(58, 8)]), 13, 80))

        gender     = np.random.choice([0, 1, 2], p=[0.45, 0.50, 0.05])
        occupation = np.random.choice(6, p=[
            [0.20, 0.40, 0.20, 0.10, 0.05, 0.05],
            [0.25, 0.35, 0.20, 0.12, 0.05, 0.03],
            [0.20, 0.25, 0.20, 0.25, 0.05, 0.05],
            [0.15, 0.15, 0.15, 0.40, 0.08, 0.07],
        ][r])
        education  = np.random.choice(4, p=[
            [0.05, 0.20, 0.60, 0.15],
            [0.08, 0.25, 0.52, 0.15],
            [0.12, 0.35, 0.40, 0.13],
            [0.20, 0.40, 0.33, 0.07],
        ][r])
        marital    = np.random.choice(3, p=[
            [0.35, 0.55, 0.10],
            [0.35, 0.50, 0.15],
            [0.35, 0.40, 0.25],
            [0.30, 0.30, 0.40],
        ][r])
        income     = np.random.choice(3, p=[
            [0.15, 0.55, 0.30],
            [0.25, 0.55, 0.20],
            [0.40, 0.45, 0.15],
            [0.55, 0.35, 0.10],
        ][r])
        living     = np.random.choice(3, p=[
            [0.10, 0.75, 0.15],
            [0.15, 0.65, 0.20],
            [0.30, 0.50, 0.20],
            [0.45, 0.40, 0.15],
        ][r])

        has_chronic   = int(np.random.random() < [0.10, 0.20, 0.35, 0.55][r])
        sleep_hours   = self._clip(np.random.normal([7.5, 6.5, 5.5, 4.5][r], 1.0), 3, 12)
        exercise      = np.random.choice(4, p=[
            [0.10, 0.20, 0.45, 0.25],
            [0.20, 0.28, 0.35, 0.17],
            [0.38, 0.30, 0.22, 0.10],
            [0.55, 0.25, 0.15, 0.05],
        ][r])
        social_support = self._clip(np.random.normal([4.2, 3.3, 2.4, 1.6][r], 0.8), 1, 5)

        return [
            avg_rt, rt_variance, total_changes, hesitation, consistency,
            max_sev, min_sev, neutral_ratio,                            # 8 behavioral
            phq_total, gad_total, phq_gad_product, combined_severity,  # 4 tier1
            age, gender, occupation, education, marital, income,
            living, has_chronic, sleep_hours, exercise, social_support  # 11 demo
        ]


#  TRAIN & EVALUATE


class ModelTrainer:
    """Huấn luyện, calibrate, đánh giá và lưu model."""

    def __init__(self):
        self.model  = None   # CalibratedClassifierCV (bao RF bên trong)
        self.scaler = None
        self.feature_importance: dict = {}

    def run(self, df: pd.DataFrame):
        X = df[FEATURE_NAMES]
        y = df["risk_category"]

        X_train, X_test, y_train, y_test = train_test_split(
            X, y, test_size=0.20, random_state=42, stratify=y
        )
        self._scale(X_train, X_test)
        self._train(X_train, y_train)
        self._evaluate(X_test, y_test)
        self._feature_importance(FEATURE_NAMES)

    def save(self, output_dir: str = "models"):
        os.makedirs(output_dir, exist_ok=True)

        joblib.dump(self.model,  os.path.join(output_dir, "risk_model.pkl"))
        joblib.dump(self.scaler, os.path.join(output_dir, "scaler.pkl"))

        # Feature importance từ RF bên trong calibrated model
        rf_base     = self.model.calibrated_classifiers_[0].estimator
        importances = rf_base.feature_importances_

        metadata = {
            "version":              "3.0.0",
            "trained_at":           datetime.now().isoformat(),
            "n_features":           len(FEATURE_NAMES),
            "feature_names":        FEATURE_NAMES,
            "classes":              RISK_CATEGORIES,
            "confidence_threshold": CONFIDENCE_THRESHOLD,
            "feature_importance":   {
                FEATURE_NAMES[i]: float(importances[i])
                for i in range(len(FEATURE_NAMES))
            },
            "feature_groups": {
                "behavioral":   FEATURE_NAMES[0:8],
                "tier1_scores": FEATURE_NAMES[8:12],
                "demographics": FEATURE_NAMES[12:23],
            },
            "improvements_v3": [
                "Split extreme_ratio -> max_severity_ratio + min_severity_ratio",
                "SMOTE oversampling for CRITICAL class",
                "Pipeline-based CV (no data leakage)",
                "Interaction features: phq_gad_product + combined_severity",
                "CalibratedClassifierCV (isotonic) for reliable probabilities",
                "Confidence threshold floor (< 60% -> clinical floor applies)",
            ],
        }
        with open(os.path.join(output_dir, "model_metadata.json"), "w", encoding="utf-8") as f:
            json.dump(metadata, f, indent=2, ensure_ascii=False)

        model_kb  = os.path.getsize(os.path.join(output_dir, "risk_model.pkl"))  / 1024
        scaler_kb = os.path.getsize(os.path.join(output_dir, "scaler.pkl"))      / 1024

        _banner("ĐÃ LƯU MODEL v3.0")
        print(f"   models/risk_model.pkl        ({model_kb:.1f} KB)")
        print(f"   models/scaler.pkl            ({scaler_kb:.1f} KB)")
        print(f"   models/model_metadata.json")

    #  Private 

    def _scale(self, X_train, X_test):
        _banner("BƯỚC 1 – CHUẨN HOÁ DỮ LIỆU")
        self.scaler   = StandardScaler()
        self._X_train = self.scaler.fit_transform(X_train)
        self._X_test  = self.scaler.transform(X_test)
        # Giữ lại DataFrame gốc (chưa scale) để Pipeline CV dùng
        self._X_train_df = X_train
        self._y_train    = None  # sẽ gán trong _train
        print(f"  Training samples : {len(X_train)}")
        print(f"  Test samples     : {len(X_test)}")
        print(f"  Số features      : {X_train.shape[1]}")

    def _train(self, X_train, y_train):
        _banner("BƯỚC 2 – SMOTE + HUẤN LUYỆN + CALIBRATE")
        self._y_train = y_train

        #  SMOTE – tăng lớp CRITICAL
        try:
            from imblearn.over_sampling import SMOTE
            n_critical  = int((y_train == 3).sum())
            target_crit = max(1200, n_critical)
            k_neighbors = min(5, n_critical - 1)
            smote       = SMOTE(
                sampling_strategy={3: target_crit},
                k_neighbors=k_neighbors,
                random_state=42,
            )
            X_res, y_res = smote.fit_resample(self._X_train, y_train)
            print(f"  SMOTE: CRITICAL {n_critical} → {int((y_res==3).sum())} mẫu ✅")
        except ImportError:
            print("    imbalanced-learn chưa cài → bỏ qua SMOTE")
            print("      Cài bằng: pip install imbalanced-learn")
            X_res, y_res = self._X_train, y_train

        # Base RandomForest (hyperparams tăng nhẹ so với v2)
        rf = RandomForestClassifier(
            n_estimators=300,
            max_depth=20,
            min_samples_split=6,
            min_samples_leaf=3,
            max_features="sqrt",
            class_weight="balanced",
            random_state=42,
            n_jobs=-1,
        )

        #  Calibrate xác suất
        print("  Đang calibrate + huấn luyện (isotonic, cv=5)…")
        self.model = CalibratedClassifierCV(rf, method="isotonic", cv=5)
        self.model.fit(X_res, y_res)
        print("  ✅ Huấn luyện & calibration xong!")

    def _evaluate(self, X_test, y_test):
        _banner("BƯỚC 3 – ĐÁNH GIÁ MODEL")

        y_pred = self.model.predict(self._X_test)
        acc    = accuracy_score(y_test, y_pred)

        #  Pipeline CV – scaler fit độc lập trong mỗi fold
        rf_pipeline = Pipeline([
            ("scaler", StandardScaler()),
            ("clf",    RandomForestClassifier(
                n_estimators=100, max_depth=20,
                min_samples_split=6, min_samples_leaf=3,
                max_features="sqrt", class_weight="balanced",
                random_state=42, n_jobs=-1,
            )),
        ])
        skf    = StratifiedKFold(n_splits=5, shuffle=True, random_state=42)
        cv_acc = cross_val_score(rf_pipeline, self._X_train_df, self._y_train,
                                 cv=skf, scoring="accuracy", n_jobs=-1)

        print(f"  Test Accuracy        : {acc:.4f}  ({acc*100:.2f}%)")
        print(f"  CV Accuracy (5-fold) : {cv_acc.mean():.4f} ± {cv_acc.std():.4f}")
        print()
        print("  Classification Report:")
        print("  " + "-" * 64)
        print(classification_report(y_test, y_pred,
                                    target_names=RISK_CATEGORIES, digits=3))

        cm = confusion_matrix(y_test, y_pred)
        print("  Confusion Matrix (hàng=thực tế, cột=dự đoán):")
        print("  " + "-" * 64)
        header = f"  {'':15}" + "".join(f"{c[:8]:>10}" for c in RISK_CATEGORIES)
        print(header)
        for i, row_name in enumerate(RISK_CATEGORIES):
            row_str = f"  {row_name:15}" + "".join(f"{cm[i][j]:>10}" for j in range(4))
            print(row_str)

        # Chỉ số quan trọng nhất: CRITICAL recall (bỏ sót = nguy hiểm)
        critical_recall = cm[3][3] / max(cm[3].sum(), 1)
        print()
        flag = "✅" if critical_recall >= 0.80 else " CẢNH BÁO"
        print(f"  {flag}  CRITICAL recall : {critical_recall:.3f}  "
              f"(ngưỡng an toàn >= 0.80)")

    def _feature_importance(self, feature_names):
        _banner("BƯỚC 4 – FEATURE IMPORTANCE")

        rf_base     = self.model.calibrated_classifiers_[0].estimator
        importances = rf_base.feature_importances_
        idx_sorted  = np.argsort(importances)[::-1]

        print(f"  {'Hạng':<5} {'Feature':<30} {'Importance':>12}  Bar")
        print("  " + "-" * 70)
        for rank, idx in enumerate(idx_sorted, 1):
            bar = "█" * int(importances[idx] * 100)
            print(f"  {rank:<5} {feature_names[idx]:<30} {importances[idx]:>10.4f}  {bar}")

        self.feature_importance = {
            feature_names[i]: float(importances[i])
            for i in range(len(feature_names))
        }



#  UTILITIES

def _banner(title: str):
    print()
    print("=" * 70)
    print(f"  {title}")
    print("=" * 70)


#  CLINICAL FLOOR – BẢO VỆ KẾT QUẢ TẦNG 2

def apply_clinical_floor(
    ml_risk_index: int,
    phq_score: int,
    gad_score: int,
    ml_confidence: float = 1.0,
) -> dict:
   
    floor_index      = 0
    override_reasons = []

    # ── Quy tắc lâm sàng cứng 
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

    if ml_confidence < CONFIDENCE_THRESHOLD and (phq_score + gad_score) >= 10:
        safe_floor = min(ml_risk_index + 1, 3)
        if safe_floor > floor_index:
            floor_index = safe_floor
            override_reasons.append(
                f"ML confidence={ml_confidence:.1%} < {CONFIDENCE_THRESHOLD:.0%} "
                f"→ tăng 1 bậc an toàn"
            )

    final_index    = max(ml_risk_index, floor_index)
    was_overridden = final_index > ml_risk_index

    return {
        "final_risk_index":  final_index,
        "final_risk_label":  RISK_CATEGORIES[final_index],
        "was_overridden":    was_overridden,
        "override_reasons":  override_reasons if was_overridden else [],
    }

#  MAIN


def main():
    _banner("MENTAL HEALTH RISK PREDICTION – TRAINING v3.0")
    print(f"  Tổng features : {len(FEATURE_NAMES)}")


    # 1. Sinh dữ liệu
    _banner("BƯỚC 0 – SINH DỮ LIỆU TRAINING")
    generator = MentalHealthDataGenerator(n_samples=6000)
    df        = generator.generate_dataset()

    print(f"  Đã sinh {len(df)} mẫu")
    print()
    print("  Phân phối nhãn (SAU KHI áp quy tắc lâm sàng):")
    dist = df["risk_category"].value_counts().sort_index()
    for cat_idx, count in dist.items():
        print(f"    [{cat_idx}] {RISK_CATEGORIES[cat_idx]:<18} : {count:>5} mẫu  "
              f"({count/len(df)*100:.1f}%)")

    # tính toàn vẹn
    v_crit = df[(df["phq_total_score"] >= 18) & (df["risk_category"] < 3)]
    v_high = df[(df["phq_total_score"] >= 15) & (df["risk_category"] < 2)]
    ok = len(v_crit) == 0 and len(v_high) == 0
    print()
    print(f"  Kiểm tra floor rule: {' PASSED' if ok else '❌ FAILED'}")
    if not ok:
        print(f"    - CRITICAL violations : {len(v_crit)}")
        print(f"    - HIGH violations     : {len(v_high)}")

    os.makedirs("data", exist_ok=True)
    df.to_csv("data/training_data_v3.csv", index=False, encoding="utf-8-sig")
    print()
    print("   Dữ liệu lưu tại: data/training_data_v3.csv")

    trainer = ModelTrainer()
    trainer.run(df)

    trainer.save()

    _banner("DEMO – TEST CASE BUG REPORT")
    print("  Input: PHQ=21, GAD=21 | ML dự đoán: LOW (0) | confidence: 81%")
    result = apply_clinical_floor(
        ml_risk_index=0,
        phq_score=21,
        gad_score=21,
        ml_confidence=0.81,
    )
    print(f"  → final_risk_label : {result['final_risk_label']}")
    print(f"  → was_overridden   : {result['was_overridden']}")
    for reason in result["override_reasons"]:
        print(f"     • {reason}")

    _banner(" done")



if __name__ == "__main__":
    main()