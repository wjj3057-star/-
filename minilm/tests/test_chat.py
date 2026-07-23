"""Tests for the conversational front-end: intent routing, bilingual replies,
and correct math routing (English + Korean)."""

from minigpt.chat import Assistant, _prepare_math


def bot():
    # point at a nonexistent ckpt so code requests hit the graceful no-model path
    return Assistant(ckpt="/nonexistent.pt", vocab="/nonexistent.json")


def test_greeting_english_and_korean():
    b = bot()
    assert "assistant" in b.respond("hello").lower()
    assert "도우미" in b.respond("안녕하세요")


def test_identity():
    assert "minigpt" in bot().respond("who are you").lower()
    assert "결합" in bot().respond("너 뭐야?")


def test_help_lists_capabilities():
    r = bot().respond("help")
    assert "Math" in r or "수학" in r


def test_thanks_and_farewell():
    b = bot()
    assert "welcome" in b.respond("thanks").lower()
    assert "가세요" in b.respond("잘가")


def test_math_routing_english():
    assert "2, 3" in bot().respond("solve x^2 - 5x + 6 = 0")


def test_math_routing_korean_integral():
    assert "1/3" in bot().respond("x^2를 0부터 1까지 적분해줘")


def test_math_routing_korean_derivative():
    assert "cos(x)" in bot().respond("미분 sin(x)*x^2")


def test_math_routing_korean_simplify():
    # the '히' in 간단히 must not leak in as a variable
    assert bot().respond("sin(x)^2 + cos(x)^2 간단히 해줘").strip().endswith("1")


def test_code_request_without_model_is_graceful():
    r = bot().respond("write code: def fibonacci(n):")
    assert "checkpoint" in r.lower() or "체크포인트" in r


def test_fallback():
    assert "not sure" in bot().respond("tell me a story about dragons").lower()


def test_prepare_math_korean_bounds():
    assert _prepare_math("x^2를 0부터 1까지 적분해줘") == "integrate x^2 from 0 to 1"


def test_prepare_math_korean_diff():
    assert _prepare_math("미분 sin(x)*x^2").startswith("diff ")
