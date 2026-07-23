"""A retrieval + reflection conversation engine — the most human-like chat that
is honestly achievable without a large pretrained model.

Three cooperating parts, all from scratch:

1. **Retrieval** over a curated bilingual (EN/KR) knowledge base of small-talk,
   using TF-IDF cosine similarity (word tokens + character trigrams, so it
   matches fuzzily and across both scripts). This gives varied, coherent
   responses to the things people actually say.

2. **Reflection** (ELIZA-style): when nothing is retrieved confidently, the
   engine transforms the user's own statement into a question ("I feel tired"
   -> "Why do you feel tired?"), pronoun-swapped. This keeps open-ended,
   personal conversation flowing.

3. **Memory**: remembers the user's name and the last thing they said, so it can
   address them and ask natural follow-ups.

This is a genuine dialogue system, not a wrapper around another model. It is far
from human-level open-domain conversation (that needs a large pretrained LM), but
within a from-scratch CPU project it is the honest ceiling — and it feels a lot
more like talking to someone than a fixed set of canned intents.
"""

from __future__ import annotations

import math
import random
import re
from dataclasses import dataclass, field

_HANGUL = re.compile(r"[가-힣]")


def is_korean(text: str) -> bool:
    return bool(_HANGUL.search(text))


# --------------------------------------------------------------------------- #
# Knowledge base                                                              #
# --------------------------------------------------------------------------- #

@dataclass
class Entry:
    tag: str
    patterns: list[str]
    en: list[str]
    ko: list[str]


def _kb() -> list[Entry]:
    E = Entry
    return [
        E("greeting",
          ["hi", "hello", "hey", "yo", "good morning", "good evening", "howdy",
           "안녕", "안녕하세요", "하이", "반가워", "좋은 아침", "여보세요"],
          ["Hey! Good to see you. How's it going?",
           "Hi there! What's on your mind today?",
           "Hello! How are you doing?"],
          ["안녕하세요! 만나서 반가워요. 오늘 어떻게 지내세요?",
           "안녕! 오늘 무슨 생각 하고 있어요?",
           "반가워요! 요즘 어떻게 지내요?"]),
        E("how_are_you",
          ["how are you", "how's it going", "how do you feel", "you ok",
           "어떻게 지내", "잘 지내", "기분 어때", "요즘 어때"],
          ["I'm doing well, thanks for asking! How about you?",
           "Pretty good — I like a good conversation. And you?",
           "Can't complain! What about you?"],
          ["잘 지내요, 물어봐 줘서 고마워요! 당신은요?",
           "좋아요 — 이런 대화를 좋아하거든요. 당신은 어때요?",
           "괜찮아요! 당신은 어떠세요?"]),
        E("im_good",
          ["i'm good", "im fine", "i am well", "doing great", "not bad",
           "잘 지내요", "좋아요", "괜찮아요", "그럭저럭"],
          ["Glad to hear it! What's been going well?",
           "That's great. Anything exciting lately?",
           "Nice — I'm happy for you."],
          ["다행이에요! 요즘 뭐가 잘 되고 있어요?",
           "좋네요. 요즘 재미있는 일 있어요?",
           "좋아요 — 잘 지낸다니 기뻐요."]),
        E("im_bad",
          ["i'm sad", "im tired", "i feel down", "i'm stressed", "not good",
           "having a hard time", "i'm bored", "i'm lonely",
           "슬퍼", "힘들어", "우울해", "피곤해", "지쳤어", "외로워", "심심해", "스트레스"],
          ["I'm sorry you're feeling that way. Do you want to talk about it?",
           "That sounds hard. I'm here — what happened?",
           "I hear you. Sometimes it helps to say it out loud. What's going on?"],
          ["그런 기분이라니 안타까워요. 얘기하고 싶으면 들어줄게요.",
           "많이 힘들겠어요. 저 여기 있어요 — 무슨 일 있었어요?",
           "이해해요. 가끔은 말로 꺼내는 게 도움이 돼요. 무슨 일이에요?"]),
        E("thanks",
          ["thanks", "thank you", "thx", "appreciate it",
           "고마워", "감사", "고맙습니다"],
          ["You're very welcome!", "Anytime — happy to help.",
           "My pleasure!"],
          ["천만에요!", "언제든지요 — 도움이 됐다니 기뻐요.", "별말씀을요!"]),
        E("bot_feelings",
          ["are you happy", "do you have feelings", "are you conscious",
           "감정 있어", "행복해", "의식 있어", "너도 감정"],
          ["I don't have feelings the way you do — but I enjoy a good chat!",
           "Not really feelings, but I do 'light up' at an interesting question.",
           "I'm software, so no true emotions — though I'm designed to be friendly."],
          ["당신 같은 감정은 없지만, 좋은 대화는 즐거워요!",
           "진짜 감정은 아니지만, 흥미로운 질문엔 '반짝'해요.",
           "저는 소프트웨어라 진짜 감정은 없어요 — 그래도 친절하게 만들어졌죠."]),
        E("hobby",
          ["what do you like", "your hobby", "what do you do for fun",
           "favorite thing", "취미", "뭐 좋아해", "뭐 하는 거 좋아"],
          ["I like untangling problems — math, code, a good question.",
           "Honestly? Helping someone figure something out. What about you?",
           "I enjoy language and logic. Do you have a hobby?"],
          ["문제를 푸는 걸 좋아해요 — 수학, 코드, 좋은 질문 같은 거요.",
           "솔직히요? 누군가 뭔가 알아내도록 돕는 거요. 당신은요?",
           "언어와 논리를 좋아해요. 취미가 있으세요?"]),
        E("joke",
          ["tell me a joke", "make me laugh", "say something funny",
           "농담", "웃겨줘", "재밌는 얘기"],
          ["Why do programmers prefer dark mode? Because light attracts bugs. 🐛",
           "There are 10 kinds of people: those who read binary and those who don't.",
           "I would tell you a UDP joke, but you might not get it."],
          ["프로그래머가 다크모드를 쓰는 이유? 밝으면 버그(벌레)가 꼬여서요. 🐛",
           "세상엔 10종류의 사람이 있죠: 이진수를 아는 사람과 모르는 사람.",
           "UDP 농담을 하고 싶은데, 안 닿을 수도 있어요."]),
        E("meaning_of_life",
          ["meaning of life", "why are we here", "what is the point",
           "삶의 의미", "인생의 의미", "왜 사는", "사는 이유"],
          ["Big question! Many say it's the connections and the meaning we make.",
           "42, according to one famous book — but I think it's what you choose to care about.",
           "I can't answer that for you, but the asking is a very human thing."],
          ["큰 질문이네요! 많은 사람은 관계와 우리가 만드는 의미라고 해요.",
           "어떤 유명한 책에선 42라고 하죠 — 하지만 당신이 소중히 여기는 것 아닐까요.",
           "제가 대신 답할 순 없지만, 그렇게 묻는 것 자체가 아주 인간적인 일이에요."]),
        E("compliment",
          ["you're smart", "you are helpful", "you're great", "good job", "nice",
           "똑똑하다", "잘한다", "대단해", "고마운데"],
          ["That's kind of you — thank you! 😊",
           "I appreciate that. I'll keep trying to be useful.",
           "You just made my day (metaphorically)."],
          ["그렇게 말해줘서 고마워요! 😊",
           "감사해요. 더 도움이 되도록 할게요.",
           "덕분에 (비유적으로) 하루가 밝아졌어요."]),
        E("insult",
          ["you're dumb", "you are stupid", "you're useless", "i hate you",
           "바보", "멍청해", "쓸모없어", "싫어"],
          ["Fair — I have real limits. Tell me what went wrong and I'll try harder.",
           "I'm sorry I let you down. What did you need?",
           "I can take it. Let's figure out how I can actually help."],
          ["맞아요 — 저는 한계가 분명해요. 뭐가 잘못됐는지 알려주면 더 노력할게요.",
           "실망시켜 드려 죄송해요. 무엇이 필요했어요?",
           "괜찮아요. 제가 실제로 어떻게 도울 수 있을지 같이 찾아봐요."]),
        E("who_made_you",
          ["who made you", "who created you", "who built you",
           "누가 만들었", "제작자", "만든 사람"],
          ["I was built as a small from-scratch project — a tiny neural model plus a math engine and this chat.",
           "I'm a homemade assistant: trained-from-scratch model + tools, no giant pretrained brain."],
          ["작은 밑바닥 프로젝트로 만들어졌어요 — 소형 신경망 + 수학엔진 + 이 대화 기능이에요.",
           "직접 만든 어시스턴트예요: 처음부터 학습한 모델 + 도구들, 거대한 사전학습 두뇌는 없어요."]),
        E("weather",
          ["how's the weather", "is it raining", "nice day",
           "날씨 어때", "비 와", "날씨"],
          ["I can't see outside, but I hope it's pleasant where you are!",
           "No windows in here — is it nice out?"],
          ["밖을 볼 수는 없지만, 당신이 있는 곳 날씨가 좋길 바라요!",
           "여긴 창문이 없어요 — 밖은 괜찮아요?"]),
        E("yes",
          ["yes", "yeah", "yep", "sure", "ok", "okay", "right",
           "응", "네", "그래", "맞아", "좋아"],
          ["Got it. Tell me more.", "Okay! What next?", "Alright — go on."],
          ["알겠어요. 더 얘기해 주세요.", "좋아요! 다음은요?", "네 — 계속하세요."]),
        E("no",
          ["no", "nope", "not really", "nah",
           "아니", "아니요", "별로", "그건 아니"],
          ["No worries. What would you prefer?", "Okay — tell me what's on your mind instead.",
           "That's fine. Where should we go from here?"],
          ["괜찮아요. 어떤 게 더 좋으세요?", "알겠어요 — 대신 무슨 생각을 하고 있는지 말해줘요.",
           "괜찮아요. 그럼 어디서부터 얘기할까요?"]),
        E("bye",
          ["bye", "goodbye", "see you", "good night", "talk later",
           "잘가", "안녕히", "잘 있어", "나중에 봐", "잘자"],
          ["Take care! Come back anytime. 👋", "See you! It was nice talking.",
           "Bye for now — good luck out there!"],
          ["잘 가요! 언제든 다시 와요. 👋", "또 봐요! 대화 즐거웠어요.",
           "그럼 이만 — 잘 지내요!"]),
    ]


# --------------------------------------------------------------------------- #
# TF-IDF retriever (from scratch)                                             #
# --------------------------------------------------------------------------- #

def _features(text: str) -> list[str]:
    t = text.lower().strip()
    words = re.findall(r"[a-z0-9]+|[가-힣]+", t)
    packed = re.sub(r"\s+", "", t)
    trigrams = ["#" + packed[i : i + 3] for i in range(len(packed) - 2)]
    return words + trigrams


class Retriever:
    """TF-IDF cosine retriever over (pattern -> entry) pairs."""

    def __init__(self, entries: list[Entry]):
        self.entries = entries
        self.docs: list[tuple[int, str]] = []  # (entry_index, pattern)
        for i, e in enumerate(entries):
            for p in e.patterns:
                self.docs.append((i, p))
        # document frequencies
        df: dict[str, int] = {}
        self._doc_feats = []
        for _, pat in self.docs:
            feats = _features(pat)
            self._doc_feats.append(feats)
            for f in set(feats):
                df[f] = df.get(f, 0) + 1
        n = len(self.docs)
        self.idf = {f: math.log((n + 1) / (c + 1)) + 1.0 for f, c in df.items()}
        self._doc_vecs = [self._vec(feats) for feats in self._doc_feats]

    def _vec(self, feats: list[str]) -> dict[str, float]:
        tf: dict[str, float] = {}
        for f in feats:
            tf[f] = tf.get(f, 0.0) + 1.0
        vec = {f: c * self.idf.get(f, 0.0) for f, c in tf.items()}
        norm = math.sqrt(sum(v * v for v in vec.values())) or 1.0
        return {f: v / norm for f, v in vec.items()}

    def match(self, text: str) -> tuple[Entry | None, float]:
        qvec = self._vec(_features(text))
        if not qvec:
            return None, 0.0
        best_i, best_score = -1, 0.0
        for di, dvec in enumerate(self._doc_vecs):
            # cosine over the smaller vector's keys
            a, b = (qvec, dvec) if len(qvec) < len(dvec) else (dvec, qvec)
            score = sum(v * b.get(k, 0.0) for k, v in a.items())
            if score > best_score:
                best_score, best_i = score, di
        if best_i < 0:
            return None, 0.0
        return self.entries[self.docs[best_i][0]], best_score


# --------------------------------------------------------------------------- #
# Reflection (ELIZA-style)                                                    #
# --------------------------------------------------------------------------- #

_SWAP = {
    "i": "you", "me": "you", "my": "your", "mine": "yours", "am": "are",
    "i'm": "you're", "myself": "yourself", "you": "I", "your": "my",
    "yours": "mine", "yourself": "myself",
}


def _reflect_en(fragment: str) -> str:
    words = re.findall(r"[a-zA-Z']+", fragment.lower())
    return " ".join(_SWAP.get(w, w) for w in words).strip()


_EN_RULES = [
    (r"\bi (?:feel|am feeling) (.+)", "Why do you feel {0}?"),
    (r"\bi(?:'m| am) (.+)", "How long have you been {0}?"),
    (r"\bi (?:think|believe) (.+)", "What makes you think {0}?"),
    (r"\bi (?:want|need) (.+)", "Why do you want {0}?"),
    (r"\bi (?:can'?t|cannot) (.+)", "What stops you from being able to {0}?"),
    (r"\bi (?:like|love) (.+)", "What do you enjoy about {0}?"),
    (r"\bbecause (.+)", "Is that the real reason?"),
    (r"\byou are (.+)", "What makes you think I'm {0}?"),
    (r"\bwhy (.+)", "Why do you think that is?"),
]

_KO_RULES = [
    (r"(.+?)하고\s*싶", "왜 {0}하려고 하세요?"),
    (r"(.+?)고\s*싶", "왜 {0}고 싶으세요?"),
    (r"(.+?)(?:느낌이|것\s*같아|기분이)", "왜 그렇게 느끼세요?"),
    (r"(.+?)(?:때문|라서|어서)", "그게 진짜 이유일까요?"),
    (r"(.+?)(?:못해|못하겠)", "무엇이 그걸 막고 있나요?"),
    (r"(.+?)(?:좋아|사랑)", "어떤 점이 좋으세요?"),
]


def reflect(text: str) -> str | None:
    if is_korean(text):
        for pat, tmpl in _KO_RULES:
            m = re.search(pat, text)
            if m:
                return tmpl.format(m.group(1).strip())
        return None
    low = text.lower()
    for pat, tmpl in _EN_RULES:
        m = re.search(pat, low)
        if m:
            frag = _reflect_en(m.group(1)) if m.groups() else ""
            return tmpl.format(frag).replace("  ", " ").strip()
    return None


# --------------------------------------------------------------------------- #
# Memory                                                                       #
# --------------------------------------------------------------------------- #

@dataclass
class Memory:
    name: str | None = None
    last_user: str = ""
    history: list[str] = field(default_factory=list)


_NAME_PATTERNS = [
    r"my name is ([A-Za-z][A-Za-z .'-]{0,30})",
    r"call me ([A-Za-z][A-Za-z .'-]{0,30})",
    r"내 이름은\s*([가-힣A-Za-z]{1,10})",
    r"제 이름은\s*([가-힣A-Za-z]{1,10})",
    r"저는\s*([가-힣A-Za-z]{1,10})(?:입니다|이에요|예요|이라고|이라|라고)",
    r"나는\s*([가-힣A-Za-z]{1,10})(?:야|이야|라고)",
]

# Korean copula / particle tails to strip off a captured name
_KO_NAME_TAIL = re.compile(r"(이라고|라고|입니다|이에요|예요|이야|이라|야|이|은|는)$")


# --------------------------------------------------------------------------- #
# Engine                                                                       #
# --------------------------------------------------------------------------- #

_GENERIC = {
    "en": [
        "Tell me more about that.",
        "Interesting — what makes you say that?",
        "I see. How does that make you feel?",
        "Go on, I'm listening.",
        "Hmm, why do you think that is?",
    ],
    "ko": [
        "그것에 대해 더 얘기해 주세요.",
        "흥미롭네요 — 왜 그렇게 생각하세요?",
        "그렇군요. 그건 기분이 어때요?",
        "계속하세요, 듣고 있어요.",
        "음, 왜 그럴까요?",
    ],
}


class ConversationEngine:
    """Retrieval + reflection + memory dialogue."""

    def __init__(self, threshold: float = 0.34, seed: int | None = None):
        self.retriever = Retriever(_kb())
        self.threshold = threshold
        self.memory = Memory()
        self.rng = random.Random(seed)
        self._last_tag: str | None = None

    def _extract_name(self, text: str) -> bool:
        """Set memory.name if the message introduces one; return True if newly set."""
        for pat in _NAME_PATTERNS:
            m = re.search(pat, text, flags=re.IGNORECASE)
            if m:
                cand = m.group(1).strip().rstrip(".")
                if is_korean(cand):
                    cand = _KO_NAME_TAIL.sub("", cand).strip() or cand
                low = cand.lower()
                if low in {"good", "fine", "ok", "okay", "sad", "tired", "sorry",
                           "happy", "here", "not", "so", "just", "back"}:
                    return False
                if cand and cand != self.memory.name:
                    self.memory.name = cand
                    return True
        return False

    def _choose(self, options: list[str]) -> str:
        # avoid immediate repetition when possible
        pick = self.rng.choice(options)
        if len(options) > 1 and pick == getattr(self, "_last_line", None):
            pick = self.rng.choice([o for o in options if o != pick])
        self._last_line = pick
        return pick

    def respond(self, text: str) -> str:
        korean = is_korean(text)
        text = text.strip()
        self.memory.history.append(text)

        # name recall (check before extraction so "what's my name" isn't a set)
        if re.search(r"(what'?s|what is) my name|내 이름이|제 이름이", text, re.IGNORECASE):
            if self.memory.name:
                return (f"You told me your name is {self.memory.name}."
                        if not korean else f"{self.memory.name}(이)라고 하셨어요.")
            return ("I don't think you've told me your name yet — what is it?"
                    if not korean else "아직 이름을 안 알려주신 것 같아요 — 뭐예요?")

        # freshly introduced name -> acknowledge naturally
        if self._extract_name(text):
            return (f"만나서 반가워요, {self.memory.name}님! 무엇을 도와드릴까요?"
                    if korean else
                    f"Nice to meet you, {self.memory.name}! How can I help?")

        # 1) retrieval
        entry, score = self.retriever.match(text)
        if entry and score >= self.threshold:
            self._last_tag = entry.tag
            resp = self._choose(entry.ko if korean else entry.en)
            # personalize greetings with the remembered name
            if entry.tag == "greeting" and self.memory.name:
                resp = (f"{resp} 다시 만나서 반가워요, {self.memory.name}님!"
                        if korean else f"{resp} Nice to see you again, {self.memory.name}!")
            self.memory.last_user = text
            return resp

        # 2) reflection
        r = reflect(text)
        if r:
            self.memory.last_user = text
            return r

        # 3) generic listening move
        self.memory.last_user = text
        return self._choose(_GENERIC["ko" if korean else "en"])
