"""Tests for the assistant front-end: routing between chat, math, and code."""

from minigpt.chat import Assistant, _prepare_math
from minigpt.converse import is_korean


def bot():
    # nonexistent ckpt so code requests hit the graceful no-model path
    return Assistant(ckpt="/nonexistent.pt", vocab="/nonexistent.json", seed=0)


def test_identity_mentions_what_it_is():
    assert "minigpt" in bot().respond("who are you").lower()
    assert "결합" in bot().respond("너 뭐야?")


def test_help_lists_capabilities():
    r = bot().respond("help")
    assert "Math" in r or "수학" in r


def test_greeting_is_conversational_and_bilingual():
    assert bot().respond("hi there").strip()
    assert is_korean(bot().respond("안녕하세요"))


def test_math_routing_english():
    assert "2, 3" in bot().respond("solve x^2 - 5x + 6 = 0")


def test_math_routing_korean_integral():
    assert "1/3" in bot().respond("x^2를 0부터 1까지 적분해줘")


def test_math_routing_korean_derivative():
    assert "cos(x)" in bot().respond("미분 sin(x)*x^2")


def test_math_routing_korean_simplify_no_leak():
    assert bot().respond("sin(x)^2 + cos(x)^2 간단히 해줘").strip().endswith("1")


def test_code_request_without_model_is_graceful():
    r = bot().respond("write code: def fibonacci(n):")
    assert "checkpoint" in r.lower() or "체크포인트" in r


def test_small_talk_goes_to_conversation_engine():
    r = bot().respond("i feel a bit lonely today").lower()
    assert r.strip()  # empathetic / reflective, never empty


def test_memory_persists_across_turns():
    b = bot()
    b.respond("my name is Dana")
    assert "Dana" in b.respond("what is my name?")


def test_prepare_math_korean_bounds():
    assert _prepare_math("x^2를 0부터 1까지 적분해줘") == "integrate x^2 from 0 to 1"


def test_prepare_math_korean_diff():
    assert _prepare_math("미분 sin(x)*x^2").startswith("diff ")
