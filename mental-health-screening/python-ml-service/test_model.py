"""
Test Script - Mental Health Risk Prediction Model v2.0
=======================================================
Kiểm tra model với 20 features:
  7 behavioral + 2 tier1 (PHQ/GAD) + 11 demographics

Cách dùng:
  python test_model.py              # chạy test cases có sẵn
  python test_model.py interactive  # nhập thủ công
"""

import numpy as np
import joblib
import json
import os
import sys

# ── Tên 20 features (đúng thứ tự khi train) ────────────────────────────
FEATURE_NAMES = [
    # Behavioral (7)
    "avg_response_time",
    "response_time_variance",
    "total_changes",
    "hesitation_score",
    "consistency_score",
    "extreme_ratio",
    "neutral_ratio",
    # Tier 1 (2)
    "phq_total_score",
    "gad_total_score",
    # Demographics (11)
    "age",
    "gender_encoded",
    "occupation_encoded",
    "education_encoded",
    "marital_encoded",
    "income_encoded",
    "living_encoded",
    "has_chronic_illness",
    "sleep_hours_avg",
    "exercise_encoded",
    "social_support_level",
]

CATEGORIES = ["LOW_RISK", "MODERATE_RISK", "HIGH_RISK", "CRITICAL_RISK"]


# ─────────────────────────────────────────────
#  LOAD MODEL
# ─────────────────────────────────────────────

def load_model():
    paths = {
        "model":    "models/risk_model.pkl",
        "scaler":   "models/scaler.pkl",
        "metadata": "models/model_metadata.json",
    }
    for k, p in paths.items():
        if not os.path.exists(p):
            print(f"❌  File không tồn tại: {p}")
            print("    Hãy chạy: python train_model.py")
            return None, None, None

    model    = joblib.load(paths["model"])
    scaler   = joblib.load(paths["scaler"])
    with open(paths["metadata"], encoding="utf-8") as f:
        metadata = json.load(f)
    return model, scaler, metadata


# ─────────────────────────────────────────────
#  PREDICT HELPER
# ─────────────────────────────────────────────

def predict(model, scaler, features: list) -> dict:
    x_scaled = scaler.transform(np.array(features, dtype=float).reshape(1, -1))
    proba    = model.predict_proba(x_scaled)[0]
    pred_idx = model.predict(x_scaled)[0]

    # Risk score tổng hợp (0–100)
    risk_score = (
        proba[0] * 15 +   # LOW_RISK      → trung tâm 15
        proba[1] * 40 +   # MODERATE_RISK → trung tâm 40
        proba[2] * 65 +   # HIGH_RISK     → trung tâm 65
        proba[3] * 88     # CRITICAL_RISK → trung tâm 88
    )
    return {
        "category":    CATEGORIES[pred_idx],
        "risk_score":  round(risk_score, 2),
        "confidence":  round(float(proba[pred_idx]), 4),
        "proba": {c: round(float(proba[i]), 4) for i, c in enumerate(CATEGORIES)},
    }


# ─────────────────────────────────────────────
#  TEST CASES
# ─────────────────────────────────────────────

TEST_CASES = [
    {
        "name":        "User A – Bình thường (LOW_RISK)",
        "description": "Trả lời nhanh, nhất quán; PHQ/GAD thấp; ngủ đủ giấc, có hỗ trợ xã hội tốt",
        "expected":    "LOW_RISK",
        "features": [
            # Behavioral
            3000, 500_000, 1, 0.08, 0.93, 0.12, 0.68,
            # Tier 1
            3, 4,
            # Demographics: age=28, Nam, Nhân viên VP, ĐH, Độc thân,
            #               Thu nhập trung bình, Sống cùng gia đình,
            #               Không bệnh mãn tính, ngủ 8h, tập 2-3/tuần, ss=5
            28, 0, 1, 2, 0, 1, 1, 0, 8.0, 2, 5,
        ],
    },
    {
        "name":        "User B – Lo âu nhẹ (MODERATE_RISK)",
        "description": "Trả lời hơi chậm; PHQ/GAD mức nhẹ; ít tập thể dục",
        "expected":    "MODERATE_RISK",
        "features": [
            # Behavioral
            8000, 2_200_000, 7, 0.36, 0.62, 0.32, 0.48,
            # Tier 1
            8, 9,
            # Demographics: age=34, Nữ, Nhân viên VP, THPT, Đã kết hôn,
            #               Thu nhập trung bình, Sống cùng gia đình,
            #               Không bệnh, ngủ 6.5h, <1/tuần, ss=3
            34, 1, 1, 1, 1, 1, 1, 0, 6.5, 1, 3,
        ],
    },
    {
        "name":        "User C – Lo âu cao (HIGH_RISK)",
        "description": "Chậm, nhiều do dự; PHQ/GAD mức vừa; thất nghiệp, ít ngủ",
        "expected":    "HIGH_RISK",
        "features": [
            # Behavioral
            17000, 6_800_000, 19, 0.64, 0.36, 0.58, 0.28,
            # Tier 1
            13, 14,
            # Demographics: age=42, Nam, Thất nghiệp, THPT, Ly hôn,
            #               Thu nhập thấp, Sống một mình,
            #               Có bệnh mãn tính, ngủ 5h, không tập, ss=2
            42, 0, 3, 1, 2, 0, 0, 1, 5.0, 0, 2,
        ],
    },
    {
        "name":        "User D – Nguy hiểm (CRITICAL_RISK)",
        "description": "Rất chậm, cực kỳ không ổn; PHQ/GAD rất cao; nhiều yếu tố nguy cơ",
        "expected":    "CRITICAL_RISK",
        "features": [
            # Behavioral
            28000, 14_000_000, 35, 0.86, 0.12, 0.84, 0.07,
            # Tier 1
            19, 18,
            # Demographics: age=22, Nữ, Thất nghiệp, THCS, Độc thân,
            #               Thu nhập thấp, Sống một mình,
            #               Có bệnh mãn tính, ngủ 4h, không tập, ss=1
            22, 1, 3, 0, 0, 0, 0, 1, 4.0, 0, 1,
        ],
    },
]


# ─────────────────────────────────────────────
#  RUNNERS
# ─────────────────────────────────────────────

def run_tests():
    print("=" * 72)
    print("  TESTING MODEL v2.0  (20 features = behavioral + tier1 + demographics)")
    print("=" * 72)

    model, scaler, metadata = load_model()
    if model is None:
        return

    print(f"\n  Model version : {metadata.get('version', '?')}")
    print(f"  Trained at    : {metadata.get('trained_at', '?')}")
    print(f"  n_features    : {metadata.get('n_features', '?')}")

    correct = 0
    for i, tc in enumerate(TEST_CASES, 1):
        print(f"\n{'─'*72}")
        print(f"  Test {i}: {tc['name']}")
        print(f"  Mô tả : {tc['description']}")

        result = predict(model, scaler, tc["features"])

        print(f"\n  Features ({len(tc['features'])} giá trị):")
        groups = [
            ("Behavioral [1-7]",       tc["features"][0:7],  FEATURE_NAMES[0:7]),
            ("Tier 1     [8-9]",        tc["features"][7:9],  FEATURE_NAMES[7:9]),
            ("Demographics [10-20]",   tc["features"][9:20], FEATURE_NAMES[9:20]),
        ]
        for grp_name, vals, names in groups:
            print(f"    ── {grp_name}")
            for name, val in zip(names, vals):
                print(f"       {name:<30} = {val}")

        print(f"\n  Kết quả dự đoán:")
        print(f"    Category   : {result['category']}")
        print(f"    Risk Score : {result['risk_score']:.2f} / 100")
        print(f"    Confidence : {result['confidence']:.2%}")
        print(f"\n  Xác suất từng nhãn:")
        for cat, prob in result["proba"].items():
            bar = "█" * int(prob * 40)
            print(f"    {cat:<18} {prob:>6.2%}  {bar}")

        ok = result["category"] == tc["expected"]
        if ok:
            print(f"\n  ✅ ĐÚNG!  (expected: {tc['expected']})")
            correct += 1
        else:
            print(f"\n  ❌ SAI!   (expected: {tc['expected']}, got: {result['category']})")

    total   = len(TEST_CASES)
    acc_pct = correct / total * 100
    print(f"\n{'='*72}")
    print(f"  KẾT QUẢ: {correct}/{total} đúng  →  Accuracy = {acc_pct:.1f}%")
    if acc_pct == 100:
        print("  🎉 Tất cả test cases đều chính xác!")
    elif acc_pct >= 75:
        print("  ✅ Model hoạt động tốt")
    else:
        print("  ⚠️  Model cần được cải thiện – hãy chạy lại train_model.py")
    print()


def run_interactive():
    """Chế độ nhập tay từng feature."""
    print("=" * 72)
    print("  INTERACTIVE TEST (nhập 'q' ở bất kỳ bước nào để thoát)")
    print("=" * 72)

    model, scaler, metadata = load_model()
    if model is None:
        return

    legends = {
        "gender_encoded":     "0=Nam  1=Nữ  2=Khác",
        "occupation_encoded": "0=Học sinh/SV  1=NV Văn phòng  2=Lao động  3=Thất nghiệp  4=Hưu  5=Khác",
        "education_encoded":  "0=THCS  1=THPT  2=Cao đẳng/ĐH  3=Sau ĐH",
        "marital_encoded":    "0=Độc thân  1=Kết hôn  2=Ly hôn/Góa",
        "income_encoded":     "0=Thấp  1=Trung bình  2=Cao",
        "living_encoded":     "0=Một mình  1=Gia đình  2=Bạn bè/KTX",
        "has_chronic_illness": "0=Không  1=Có",
        "exercise_encoded":   "0=Không  1=<1/tuần  2=1-3/tuần  3=>3/tuần",
    }

    while True:
        print("\nNhập giá trị 20 features:")
        features = []
        cancelled = False
        for idx, name in enumerate(FEATURE_NAMES, 1):
            hint = legends.get(name, "")
            prompt = f"  {idx:>2}. {name:<30}{('('+hint+')') if hint else ''}: "
            val = input(prompt).strip()
            if val.lower() == "q":
                cancelled = True
                break
            try:
                features.append(float(val))
            except ValueError:
                print("     ⚠️  Giá trị không hợp lệ, mặc định = 0")
                features.append(0.0)

        if cancelled:
            break

        result = predict(model, scaler, features)
        print(f"\n  ── Kết quả ──────────────────────────────────")
        print(f"  Category   : {result['category']}")
        print(f"  Risk Score : {result['risk_score']:.2f} / 100")
        print(f"  Confidence : {result['confidence']:.2%}")
        for cat, prob in result["proba"].items():
            bar = "█" * int(prob * 40)
            print(f"  {cat:<18} {prob:>6.2%}  {bar}")

        again = input("\n  Tiếp tục? (y/n): ").strip().lower()
        if again != "y":
            break

    print("  Tạm biệt!")


# ─────────────────────────────────────────────
#  ENTRY POINT
# ─────────────────────────────────────────────

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "interactive":
        run_interactive()
    else:
        run_tests()