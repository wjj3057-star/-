"""Tests for the retrieval + reflection + memory conversation engine."""

from minigpt.converse import (
    ConversationEngine,
    Retriever,
    _kb,
    is_korean,
    reflect,
)


def test_retriever_matches_greeting():
    r = Retriever(_kb())
    e, s = r.match("hello there!")
    assert e is not None and e.tag == "greeting" and s > 0.3


def test_retriever_matches_korean_joke():
    r = Retriever(_kb())
    e, s = r.match("농담 하나 해줘")
    assert e is not None and e.tag == "joke"


def test_retriever_matches_emotion():
    r = Retriever(_kb())
    e, _ = r.match("i'm so tired today")
    assert e.tag == "im_bad"


def test_reflect_feel():
    assert "why do you feel" in reflect("i feel anxious").lower()


def test_reflect_want():
    assert "why do you want" in reflect("i want to travel").lower()


def test_reflect_think():
    assert "what makes you think" in reflect("i think it is broken").lower()


def test_reflect_korean_want():
    out = reflect("여행하고 싶어")
    assert out and "하려고" in out


def test_reflect_returns_none_on_plain():
    assert reflect("the sky is blue") is None


def test_memory_remembers_name_english():
    c = ConversationEngine(seed=0)
    ack = c.respond("my name is Sam")
    assert "Sam" in ack
    assert c.memory.name == "Sam"
    assert "Sam" in c.respond("what is my name?")


def test_memory_korean_name_strips_copula():
    c = ConversationEngine(seed=0)
    c.respond("내 이름은 지훈이야")
    assert c.memory.name == "지훈"
    assert "지훈" in c.respond("제 이름이 뭐죠?")


def test_bilingual_replies_match_language():
    c = ConversationEngine(seed=0)
    assert not is_korean(c.respond("hello"))
    assert is_korean(ConversationEngine(seed=0).respond("안녕하세요"))


def test_emotional_input_is_empathetic_not_reflected():
    c = ConversationEngine(seed=0)
    out = c.respond("i'm feeling really down").lower()
    # should hit the empathetic entry, which asks to talk / what's going on
    assert any(w in out for w in ("talk", "here", "going on", "hard"))


def test_generic_fallback_is_nonempty():
    c = ConversationEngine(seed=0)
    assert c.respond("purple monkey dishwasher").strip()
