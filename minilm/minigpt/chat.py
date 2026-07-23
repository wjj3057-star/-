"""A small conversational front-end for the mini assistant.

This ties the pieces into something you can talk to: it handles simple
communication (greetings, identity, help, thanks) with intent rules, routes
math questions to the exact symbolic engine (mathsolve), and routes code
requests to the trained neural model's extended-thinking generator. It answers
in Korean when you write Korean, otherwise in English.

    python -m minigpt.chat            # interactive
    python -m minigpt.chat --once "integrate x^2 from 0 to 1"

This is deliberately a *router*, not an open-domain chatbot: the small
from-scratch model can't hold a general conversation, but the assistant as a
whole gives correct math, real code generation, and basic dialogue.
"""

from __future__ import annotations

import argparse
import re

from .mathsolve import MathError, looks_like_math, solve_math

_HANGUL = re.compile(r"[가-힣]")


def _ko(text: str) -> bool:
    return bool(_HANGUL.search(text))


# bilingual canned responses: (english, korean)
_LINES = {
    "greeting": (
        "Hi! I'm a small assistant. I can chat a little, solve math exactly, "
        "and generate code. Try: 'integrate x^2 from 0 to 1'.",
        "안녕하세요! 저는 작은 도우미예요. 간단한 대화와 정확한 수학 풀이, 코드 생성을 할 수 있어요. "
        "예: 'x^2를 0부터 1까지 적분' 또는 'solve x^2-5x+6=0'.",
    ),
    "identity": (
        "I'm minigpt-assistant: a from-scratch neural code model paired with a "
        "symbolic math engine. The math is exact (SymPy); the code comes from a "
        "small GPT trained from random init.",
        "저는 minigpt-assistant예요. 밑바닥부터 학습한 신경망 코드 모델 + 기호수학 엔진의 결합이에요. "
        "수학은 SymPy로 정확하게 풀고, 코드는 처음부터 학습한 소형 GPT가 생성해요.",
    ),
    "help": (
        "I can:\n"
        "  • Math  — solve / diff / integrate / limit / factor / expand / series / sum\n"
        "            e.g. 'solve 2x+3y=7, x-y=1', 'limit sin(x)/x as x->0'\n"
        "  • Code  — 'write code: def fibonacci(n):'  (uses the trained model)\n"
        "  • Chat  — greetings, who you are, help, thanks",
        "할 수 있는 것:\n"
        "  • 수학 — 방정식/미분/적분/극한/인수분해/전개/급수/합\n"
        "           예: 'solve 2x+3y=7, x-y=1', '극한 sin(x)/x as x->0'\n"
        "  • 코드 — 'write code: def fibonacci(n):' (학습된 모델 사용)\n"
        "  • 대화 — 인사, 정체성, 도움말, 감사",
    ),
    "thanks": ("You're welcome!", "천만에요!"),
    "farewell": ("Bye! 👋", "안녕히 가세요! 👋"),
    "fallback": (
        "I'm not sure how to answer that. I'm best at math and code — try "
        "'help' to see examples.",
        "그건 잘 모르겠어요. 저는 수학과 코드에 강해요 — 'help'를 입력하면 예시를 볼 수 있어요.",
    ),
    "math_error": (
        "I couldn't parse that math. Try e.g. 'solve x^2 - 4 = 0'.",
        "그 수식을 이해하지 못했어요. 예: 'solve x^2 - 4 = 0' 처럼 입력해 주세요.",
    ),
    "no_model": (
        "Code generation needs a trained checkpoint (out/ckpt.pt). Train one "
        "with `python -m minigpt.train`, then ask again.",
        "코드 생성에는 학습된 체크포인트(out/ckpt.pt)가 필요해요. "
        "`python -m minigpt.train`으로 학습한 뒤 다시 물어봐 주세요.",
    ),
}


def _say(key: str, korean: bool) -> str:
    en, ko = _LINES[key]
    return ko if korean else en


# intent patterns (checked in order); Korean + English cues
_INTENTS = [
    ("greeting", r"\b(hi|hello|hey|yo|greetings)\b|안녕|반가|하이"),
    ("identity", r"who\s+are\s+you|what\s+are\s+you|your\s+name|누구|정체|너\s*뭐|넌\s*뭐|자기소개"),
    ("help", r"\bhelp\b|what\s+can\s+you|도움말|뭐\s*할\s*수|기능|사용법|메뉴"),
    ("thanks", r"\b(thanks|thank\s*you|thx)\b|고마|감사"),
    ("farewell", r"\b(bye|goodbye|see\s*you|quit|exit)\b|잘\s*가|안녕히|종료|나가"),
]

_CODE_CUE = re.compile(
    r"\b(code|function|write|implement)\b|코드|함수|짜줘|구현|작성|만들어",
    re.IGNORECASE,
)
_MATH_CUE = re.compile(
    r"계산|풀어|풀이|미분|적분|극한|인수분해|전개|방정식|급수",
)


class Assistant:
    def __init__(self, ckpt: str = "out/ckpt.pt", vocab: str = "out/vocab.json",
                 code_effort: str = "high"):
        self.ckpt = ckpt
        self.vocab = vocab
        self.code_effort = code_effort
        self._model = None
        self._tok = None

    # lazy: only pay the torch/model cost when a code request arrives
    def _ensure_model(self) -> bool:
        if self._model is not None:
            return True
        import os
        if not (os.path.exists(self.ckpt) and os.path.exists(self.vocab)):
            return False
        import torch
        from .data import CharTokenizer
        from .model import GPT, GPTConfig
        self._tok = CharTokenizer.load(self.vocab)
        c = torch.load(self.ckpt, map_location="cpu")
        self._model = GPT(GPTConfig(**c["config"]))
        self._model.load_state_dict(c["model"])
        self._model.eval()
        return True

    def _code(self, text: str, korean: bool) -> str:
        if not self._ensure_model():
            return _say("no_model", korean)
        from .reasoning import think
        # use the part after a colon as the prompt if present
        prompt = text.split(":", 1)[1].strip() if ":" in text else "def "
        if not prompt:
            prompt = "def "
        result = think(self._model, self._tok, prompt, effort=self.code_effort)
        header = "생성한 코드" if korean else "generated code"
        return f"[{header}]\n{result.prompt}{result.best.answer}"

    def respond(self, text: str) -> str:
        korean = _ko(text)
        msg = text.strip()
        if not msg:
            return _say("fallback", korean)

        # 1) explicit intents
        for name, pattern in _INTENTS:
            if re.search(pattern, msg, flags=re.IGNORECASE):
                return _say(name, korean)

        # 2) code requests -> neural model
        if _CODE_CUE.search(msg):
            return self._code(msg, korean)

        # 3) math -> symbolic engine
        if _MATH_CUE.search(msg) or looks_like_math(msg):
            try:
                return solve_math(_prepare_math(msg)).text
            except MathError:
                return _say("math_error", korean)

        # 4) give up gracefully
        return _say("fallback", korean)


# Korean math verb (comes last in a sentence) -> English command (comes first)
_KO_OPS = [
    ("인수분해", "factor"), ("미분", "diff"), ("적분", "integrate"),
    ("극한", "limit"), ("전개", "expand"), ("테일러", "series"),
    ("급수", "series"), ("간단", "simplify"), ("정리", "simplify"),
    ("방정식", "solve"), ("풀", "solve"),
]


def _prepare_math(text: str) -> str:
    """Turn a Korean/English math request into the engine's command grammar.

    e.g. 'x^2를 0부터 1까지 적분해줘'  ->  'integrate x^2 from 0 to 1'
         '미분 sin(x)*x^2'            ->  'diff sin(x)*x^2'
    """
    t = text
    op = None
    for ko, en in _KO_OPS:
        if ko in t:
            op = op or en
            t = t.replace(ko, " ")
    # polite / verb tails
    t = re.sub(r"(해\s*주세요|해줘|구해줘|계산해줘|계산해|계산|구해|알려|하게|하기|해|히|줘|주세요)", " ", t)
    # "A부터 B까지" -> "from A to B"
    t = re.sub(r"(\S+?)\s*부터\s*(\S+?)\s*까지", r"from \1 to \2", t)
    t = t.replace("무한대", "oo").replace("무한", "oo")
    # strip trailing Korean particles on tokens
    t = re.sub(r"(를|을|는|은|가|이|의|에서|에게|에|으로|로)(?=\s|$)", " ", t)
    # English prose
    t = re.sub(r"\b(please|compute|what\s+is|whats|calculate)\b", " ", t, flags=re.IGNORECASE)
    t = re.sub(r"\s+", " ", t).strip(" ?.")
    return f"{op} {t}".strip() if op else t


def main():
    p = argparse.ArgumentParser(description="Chat with the mini assistant.")
    p.add_argument("--once", help="Answer a single message and exit.")
    p.add_argument("--ckpt", default="out/ckpt.pt")
    p.add_argument("--vocab", default="out/vocab.json")
    args = p.parse_args()

    bot = Assistant(ckpt=args.ckpt, vocab=args.vocab)
    if args.once is not None:
        print(bot.respond(args.once))
        return

    print("mini assistant — say 'help' for what I can do, 'bye' to quit.\n")
    while True:
        try:
            user = input("you> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if re.search(r"\b(bye|quit|exit)\b|종료|나가", user, re.IGNORECASE):
            print("bot> " + bot.respond(user))
            break
        if not user:
            continue
        print("bot> " + bot.respond(user) + "\n")


if __name__ == "__main__":
    main()
