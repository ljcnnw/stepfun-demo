import logging
import re
import time
import unicodedata
from importlib.metadata import version

import jiwer
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict, Field

NORMALIZER_VERSION = "zh-v2"
SCORING_VERSION = "asr-eval-pass-v2"
PUNCTUATION = "\u201c\u201d\u2018\u2019'\"\uff0c\u3002\uff01\uff1f\u3001\uff1a\uff1b,.!?;:()[]{}<>\u300a\u300b\u3010\u3011"
PUNCTUATION_WITHOUT_DECIMAL_POINT = PUNCTUATION.replace(".", "")
CHINESE_DIGITS = {
    "\u96f6": "0", "\u3007": "0", "\u4e00": "1", "\u4e8c": "2", "\u4e09": "3", "\u56db": "4",
    "\u4e94": "5", "\u516d": "6", "\u4e03": "7", "\u516b": "8", "\u4e5d": "9", "\u4e24": "2",
}
CHINESE_SMALL_UNITS = {"\u5341": 10, "\u767e": 100, "\u5343": 1000}
CHINESE_BIG_UNITS = {"\u4e07": 10_000, "\u4ebf": 100_000_000}
NUMERIC_EXPRESSION = re.compile(r"[0-9\u96f6\u3007\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341\u767e\u5343\u4e07\u4ebf\u4e24\u70b9]+")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger("asr_evaluator")

app = FastAPI(title="ASR Evaluator", version="1.0.0")


class ScoreRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    reference_text: str = Field(alias="referenceText")
    transcript: str
    text_mode: str = Field(default="loose", alias="textMode")
    critical_terms: list[str] = Field(default_factory=list, alias="criticalTerms")
    acceptable_texts: list[str] = Field(default_factory=list, alias="acceptableTexts")
    pass_rule_type: str = Field(default="cer", alias="passRuleType")
    pass_threshold: float = Field(default=0.2, alias="passThreshold")


def normalize_text(value: str, mode: str) -> str:
    normalized = unicodedata.normalize("NFKC", value or "")
    if mode == "strict":
        return normalized.strip()
    if mode != "loose":
        raise ValueError("textMode must be strict or loose")
    normalized = normalize_numeric_expressions(normalized.lower())
    translation = str.maketrans({character: " " for character in PUNCTUATION_WITHOUT_DECIMAL_POINT})
    normalized = normalized.translate(translation)
    # Preserve decimal points only when they join two digits. Other periods remain punctuation.
    normalized = re.sub(r"(?<!\d)\.|\.(?!\d)", " ", normalized)
    return " ".join(normalized.split())


def normalize_numeric_expressions(value: str) -> str:
    return NUMERIC_EXPRESSION.sub(lambda match: normalize_numeric_expression(match.group()), value)


def normalize_numeric_expression(value: str) -> str:
    if value.count("\u70b9") > 1:
        return value
    if "\u70b9" in value:
        integer_part, decimal_part = value.split("\u70b9", 1)
        normalized_integer = normalize_integer_expression(integer_part)
        normalized_decimal = normalize_digit_sequence(decimal_part)
        if normalized_integer is None or normalized_decimal is None:
            return value
        return f"{normalized_integer}.{normalized_decimal}"
    normalized_integer = normalize_integer_expression(value)
    return normalized_integer if normalized_integer is not None else value


def normalize_integer_expression(value: str) -> str | None:
    if not value:
        return None
    if not any(character in CHINESE_SMALL_UNITS or character in CHINESE_BIG_UNITS for character in value):
        return normalize_digit_sequence(value)

    total = 0
    section = 0
    current: int | None = None
    index = 0
    while index < len(value):
        character = value[index]
        if character.isdigit():
            end = index
            while end < len(value) and value[end].isdigit():
                end += 1
            current = int(value[index:end])
            index = end
            continue
        if character in CHINESE_DIGITS:
            current = int(CHINESE_DIGITS[character])
        elif character in CHINESE_SMALL_UNITS:
            section += (1 if current is None else current) * CHINESE_SMALL_UNITS[character]
            current = None
        elif character in CHINESE_BIG_UNITS:
            if section == 0 and current is None and total:
                total *= CHINESE_BIG_UNITS[character]
            else:
                section += 0 if current is None else current
                total += section * CHINESE_BIG_UNITS[character]
            section = 0
            current = None
        else:
            return None
        index += 1
    return str(total + section + (0 if current is None else current))


def normalize_digit_sequence(value: str) -> str | None:
    digits: list[str] = []
    for character in value:
        if character.isdigit():
            digits.append(character)
        elif character in CHINESE_DIGITS:
            digits.append(CHINESE_DIGITS[character])
        else:
            return None
    return "".join(digits) if digits else None


def character_text(value: str, mode: str) -> str:
    return "".join(normalize_text(value, mode).split())


def wer_tokens(value: str, mode: str) -> list[str]:
    normalized = normalize_text(value, mode)
    tokens: list[str] = []
    buffer: list[str] = []

    def flush() -> None:
        if buffer:
            tokens.append("".join(buffer))
            buffer.clear()

    for character in normalized:
        if character.isspace():
            flush()
        elif "\u4e00" <= character <= "\u9fff":
            flush()
            tokens.append(character)
        elif character.isascii() and character.isalnum():
            buffer.append(character)
        else:
            flush()
            tokens.append(character)
    flush()
    return tokens


def entity_score(transcript: str, critical_terms: list[str], mode: str) -> tuple[int | None, int | None, float | None, list[str]]:
    terms = [character_text(term, mode) for term in critical_terms]
    terms = [term for term in terms if term]
    if not terms:
        return None, None, None, []
    normalized_transcript = character_text(transcript, mode)
    missed = [term for term in terms if term not in normalized_transcript]
    matched = len(terms) - len(missed)
    return matched, len(terms), matched / len(terms), missed


def is_passed(rule: str, threshold: float, cer: float, entity_accuracy: float | None) -> bool:
    if rule == "entity":
        return cer <= threshold if entity_accuracy is None else entity_accuracy == 1.0
    if rule == "mixed":
        return cer <= threshold and (entity_accuracy is None or entity_accuracy == 1.0)
    if rule == "cer":
        return cer <= threshold
    raise ValueError("passRuleType must be cer, entity, or mixed")


def pass_reason(rule: str, threshold: float, cer: float, entity_accuracy: float | None) -> str:
    if rule == "entity" and entity_accuracy is not None:
        return "关键实体全部命中" if entity_accuracy == 1.0 else "关键实体未全部命中"
    if rule == "mixed" and entity_accuracy is not None and entity_accuracy != 1.0:
        return "关键实体未全部命中"
    return "CER达标" if cer <= threshold else "CER超过阈值"


def score_reference(reference: str, transcript: str, mode: str) -> tuple[str, str, float, int, int, int, float, int, int, int]:
    normalized_reference = normalize_text(reference, mode)
    normalized_transcript = normalize_text(transcript, mode)
    reference_characters = character_text(reference, mode)
    transcript_characters = character_text(transcript, mode)
    cer, character_substitutions, character_insertions, character_deletions = error_measures(
        reference_characters,
        transcript_characters,
        jiwer.process_characters,
    )
    reference_words = " ".join(wer_tokens(reference, mode))
    transcript_words = " ".join(wer_tokens(transcript, mode))
    wer, word_substitutions, word_insertions, word_deletions = error_measures(
        reference_words,
        transcript_words,
        jiwer.process_words,
    )
    return (
        normalized_reference,
        normalized_transcript,
        cer,
        character_substitutions,
        character_insertions,
        character_deletions,
        wer,
        word_substitutions,
        word_insertions,
        word_deletions,
    )


def error_measures(reference: str, hypothesis: str, processor) -> tuple[float, int, int, int]:
    if not reference:
        if not hypothesis:
            return 0.0, 0, 0, 0
        insertion_count = len(hypothesis) if processor is jiwer.process_characters else len(hypothesis.split())
        return 1.0, 0, insertion_count, 0
    output = processor(reference, hypothesis)
    return float(output.wer if hasattr(output, "wer") else output.cer), output.substitutions, output.insertions, output.deletions


@app.get("/health")
def health() -> dict[str, str]:
    jiwer_version = version("jiwer")
    logger.info(
        "health check service=asr-evaluator normalizerVersion=%s jiwerVersion=%s",
        NORMALIZER_VERSION,
        jiwer_version,
    )
    return {
        "service": "asr-evaluator",
        "status": "ok",
        "normalizerVersion": NORMALIZER_VERSION,
        "scoringVersion": SCORING_VERSION,
        "jiwerVersion": jiwer_version,
    }


@app.post("/v1/score")
def score(request: ScoreRequest) -> dict:
    started_at = time.perf_counter()
    logger.info(
        "score request mode=%s passRule=%s threshold=%.4f referenceLen=%d transcriptLen=%d criticalTerms=%d",
        request.text_mode,
        request.pass_rule_type,
        request.pass_threshold,
        len(request.reference_text or ""),
        len(request.transcript or ""),
        len(request.critical_terms),
    )
    try:
        references = [request.reference_text, *request.acceptable_texts]
        references = list(dict.fromkeys(reference for reference in references if reference and reference.strip()))
        if not references:
            references = [""]
        scored_references = [(reference, score_reference(reference, request.transcript, request.text_mode)) for reference in references]
        reference_used, score_values = min(scored_references, key=lambda item: (item[1][2], item[1][6]))
        (
            normalized_reference,
            normalized_transcript,
            cer,
            character_substitutions,
            character_insertions,
            character_deletions,
            wer,
            word_substitutions,
            word_insertions,
            word_deletions,
        ) = score_values
        matched, total, entity_accuracy, missed_terms = entity_score(
            request.transcript,
            request.critical_terms,
            request.text_mode,
        )
        sentence_accuracy = normalized_reference == normalized_transcript
        response = {
            "normalizerVersion": NORMALIZER_VERSION,
            "scoringVersion": SCORING_VERSION,
            "referenceVariantUsed": reference_used,
            "normalizedReference": normalized_reference,
            "normalizedTranscript": normalized_transcript,
            "cer": cer,
            "wer": wer,
            "sentenceAccuracy": sentence_accuracy,
            "entityAccuracy": entity_accuracy,
            "entityMatchedCount": matched,
            "entityTotalCount": total,
            "entityMissedTerms": missed_terms,
            "characterSubstitutions": character_substitutions,
            "characterInsertions": character_insertions,
            "characterDeletions": character_deletions,
            "wordSubstitutions": word_substitutions,
            "wordInsertions": word_insertions,
            "wordDeletions": word_deletions,
            "pass": is_passed(
                request.pass_rule_type,
                request.pass_threshold,
                cer,
                entity_accuracy,
            ),
        }
        response["passReason"] = pass_reason(request.pass_rule_type, request.pass_threshold, cer, entity_accuracy)
        logger.info(
            "score success cer=%.4f wer=%.4f sentenceAccuracy=%s entityAccuracy=%s pass=%s elapsedMs=%.2f",
            response["cer"],
            response["wer"],
            response["sentenceAccuracy"],
            response["entityAccuracy"],
            response["pass"],
            (time.perf_counter() - started_at) * 1000,
        )
        return response
    except ValueError as error:
        logger.warning("score validation failed error=%s", error)
        raise HTTPException(status_code=422, detail=str(error)) from error
    except Exception:
        logger.exception("score failed unexpectedly")
        raise
