from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def score(payload: dict) -> dict:
    response = client.post("/v1/score", json=payload)
    assert response.status_code == 200, response.text
    return response.json()


def test_health_exposes_scoring_versions() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["normalizerVersion"] == "zh-v2"
    assert response.json()["jiwerVersion"] == "3.1.0"


def test_loose_mode_normalizes_punctuation_and_case() -> None:
    result = score({
        "referenceText": "您好，MACD 是 123！",
        "transcript": "您好 macd 是 123",
        "textMode": "loose",
        "passRuleType": "cer",
        "passThreshold": 0,
    })
    assert result["cer"] == 0
    assert result["pass"] is True


def test_loose_mode_normalizes_chinese_number_values_and_entities() -> None:
    result = score({
        "referenceText": "我想买15股，价格是123.05元",
        "transcript": "我想买十五股价格是一百二十三点零五元",
        "textMode": "loose",
        "criticalTerms": ["十五", "123.05"],
        "passRuleType": "mixed",
        "passThreshold": 0,
    })
    assert result["normalizedReference"] == "我想买15股 价格是123.05元"
    assert result["normalizedTranscript"] == "我想买15股价格是123.05元"
    assert result["cer"] == 0
    assert result["wer"] == 0
    assert result["entityAccuracy"] == 1
    assert result["pass"] is True


def test_loose_mode_preserves_leading_zero_for_digit_sequences() -> None:
    result = score({
        "referenceText": "验证码是0123",
        "transcript": "验证码是零一二三",
        "textMode": "loose",
        "passRuleType": "cer",
        "passThreshold": 0,
    })
    assert result["normalizedTranscript"] == "验证码是0123"
    assert result["cer"] == 0
    assert result["pass"] is True


def test_cer_rule_uses_threshold_even_when_sentence_is_not_exact() -> None:
    result = score({
        "referenceText": "你好，世界",
        "transcript": "你好世界",
        "textMode": "strict",
        "passRuleType": "cer",
        "passThreshold": 1,
    })
    assert result["sentenceAccuracy"] is False
    assert result["pass"] is True


def test_strict_mode_cer_rule_still_uses_cer_threshold() -> None:
    result = score({
        "referenceText": "我想买15股",
        "transcript": "我想买十五股",
        "textMode": "strict",
        "passRuleType": "cer",
        "passThreshold": 1,
    })
    assert result["normalizedReference"] == "我想买15股"
    assert result["normalizedTranscript"] == "我想买十五股"
    assert result["sentenceAccuracy"] is False
    assert result["pass"] is True


def test_entity_and_mixed_rules() -> None:
    entity = score({
        "referenceText": "订单号是12345",
        "transcript": "订单号是12345",
        "criticalTerms": ["12345"],
        "passRuleType": "entity",
    })
    assert entity["entityAccuracy"] == 1
    assert entity["pass"] is True

    mixed = score({
        "referenceText": "订单号是12345",
        "transcript": "订单号是54321",
        "criticalTerms": ["12345"],
        "passRuleType": "mixed",
        "passThreshold": 1,
    })
    assert mixed["pass"] is False
    assert mixed["entityMissedTerms"] == ["12345"]


def test_entity_rules_without_critical_terms_fall_back_to_cer() -> None:
    entity = score({
        "referenceText": "你好，世界",
        "transcript": "你好世界",
        "passRuleType": "entity",
        "passThreshold": 0,
    })
    assert entity["entityAccuracy"] is None
    assert entity["cer"] == 0
    assert entity["pass"] is True

    mixed = score({
        "referenceText": "你好，世界",
        "transcript": "你好世界",
        "passRuleType": "mixed",
        "passThreshold": 0,
    })
    assert mixed["entityAccuracy"] is None
    assert mixed["cer"] == 0
    assert mixed["pass"] is True


def test_acceptable_text_uses_best_matching_reference_variant() -> None:
    result = score({
        "referenceText": "我想买㗎",
        "acceptableTexts": ["我想买噶"],
        "transcript": "我想买噶",
        "passRuleType": "cer",
        "passThreshold": 0,
    })
    assert result["referenceVariantUsed"] == "我想买噶"
    assert result["cer"] == 0
    assert result["pass"] is True
    assert result["passReason"] == "CER达标"


def test_empty_reference_uses_existing_zero_or_one_semantics() -> None:
    empty = score({"referenceText": "", "transcript": "", "passRuleType": "cer", "passThreshold": 0})
    assert empty["cer"] == 0
    non_empty = score({"referenceText": "", "transcript": "结果", "passRuleType": "cer", "passThreshold": 1})
    assert non_empty["cer"] == 1
