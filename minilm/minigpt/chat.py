"""The mini assistant's front-end: natural conversation + exact math + code.

Routing per message:
  • help / identity  -> stable, accurate answers about what this actually is
  • code requests    -> the trained neural model's extended-thinking generator
  • math             -> the exact symbolic engine (mathsolve)
  • everything else  -> the retrieval + reflection + memory conversation engine
                        (converse.py), the most human-like chat achievable here

It answers in Korean when you write Korean, otherwise in English, and remembers
your name across the session.

    python -m minigpt.chat
    python -m minigpt.chat --once "integrate x^2 from 0 to 1"
"""

from __future__ import annotations

import argparse
import re

from .converse import ConversationEngine, is_korean
from .mathsolve import MathError, looks_like_math, solve_math

_HELP_RE = re.compile(
    r"\bhelp\b|what can you do|도움말|뭐\s*할\s*수|기능|사용법|메뉴", re.IGNORECASE
)
_IDENTITY_RE = re.compile(
    r"who are you|what are you|your name|누구(세요|야)?|정체|너\s*뭐|넌\s*뭐|자기소개",
    re.IGNORECASE,
)
_CODE_CUE = re.compile(
    r"\b(code|function|implement)\b|write (me )?(a )?(function|code)|코드|함수|짜줘|구현",
    re.IGNORECASE,
)
_MATH_CUE = re.compile(r"계산|풀어|풀이|미분|적분|극한|인수분해|전개|방정식|급수")

_HELP = (
    "I can:\n"
    "  • Chat  — greetings, small talk, how you're feeling; I remember your name\n"
    "  • Math  — solve / diff / integrate / limit / factor / expand / series / sum\n"
    "            e.g. 'solve 2x+3y=7, x-y=1', 'limit sin(x)/x as x->0'\n"
    "  • Code  — 'write code: def fibonacci(n):'  (uses the trained model)",
    "할 수 있는 것:\n"
    "  • 대화 — 인사, 잡담, 기분 이야기 (이름도 기억해요)\n"
    "  • 수학 — 방정식/미분/적분/극한/인수분해/전개/급수/합\n"
    "           예: 'solve 2x+3y=7, x-y=1', '극한 sin(x)/x as x->0'\n"
    "  • 코드 — 'write code: def fibonacci(n):' (학습된 모델 사용)",
)
_IDENTITY = (
    "I'm minigpt-assistant: a from-scratch neural code model, an exact symbolic "
    "math engine, and a retrieval-based conversation engine — all home-built, no "
    "giant pretrained model behind me. So I chat simply, but my math is exact.",
    "저는 minigpt-assistant예요. 밑바닥부터 학습한 신경망 코드 모델 + 정확한 기호수학 엔진 "
    "+ 검색기반 대화 엔진의 결합이에요. 거대한 사전학습 모델은 없어서 대화는 소박하지만, "
    "수학은 정확해요.",
)
_NO_MODEL = (
    "Code generation needs a trained checkpoint (out/ckpt.pt). Train one with "
    "`python -m minigpt.train`, then ask again.",
    "코드 생성에는 학습된 체크포인트(out/ckpt.pt)가 필요해요. "
    "`python -m minigpt.train`으로 학습한 뒤 다시 물어봐 주세요.",
)
_MATH_ERR = (
    "I couldn't parse that math. Try e.g. 'solve x^2 - 4 = 0'.",
    "그 수식을 이해하지 못했어요. 예: 'solve x^2 - 4 = 0' 처럼 입력해 주세요.",
)


class Assistant:
    def __init__(self, ckpt: str = "out/ckpt.pt", vocab: str = "out/vocab.json",
                 code_effort: str = "high", seed: int | None = None):
        self.ckpt = ckpt
        self.vocab = vocab
        self.code_effort = code_effort
        self.convo = ConversationEngine(seed=seed)
        self._model = None
        self._tok = None

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
            return _NO_MODEL[korean]
        from .reasoning import think
        prompt = text.split(":", 1)[1].strip() if ":" in text else "def "
        result = think(self._model, self._tok, prompt or "def ", effort=self.code_effort)
        header = "생성한 코드" if korean else "generated code"
        return f"[{header}]\n{result.prompt}{result.best.answer}"

    def respond(self, text: str) -> str:
        korean = is_korean(text)
        msg = text.strip()
        if not msg:
            return self.convo.respond(msg)

        # accurate meta answers
        if _HELP_RE.search(msg):
            return _HELP[korean]
        if _IDENTITY_RE.search(msg):
            return _IDENTITY[korean]

        # code -> neural model
        if _CODE_CUE.search(msg):
            return self._code(msg, korean)

        # math -> symbolic engine
        if _MATH_CUE.search(msg) or looks_like_math(msg):
            try:
                return solve_math(_prepare_math(msg)).text
            except MathError:
                return _MATH_ERR[korean]

        # natural conversation
        return self.convo.respond(msg)


# Korean math verb (comes last) -> English command (comes first)
_KO_OPS = [
    ("인수분해", "factor"), ("미분", "diff"), ("적분", "integrate"),
    ("극한", "limit"), ("전개", "expand"), ("테일러", "series"),
    ("급수", "series"), ("간단", "simplify"), ("정리", "simplify"),
    ("방정식", "solve"), ("풀", "solve"),
]


def _prepare_math(text: str) -> str:
    """Turn a Korean/English math request into the engine's command grammar."""
    t = text
    op = None
    for ko, en in _KO_OPS:
        if ko in t:
            op = op or en
            t = t.replace(ko, " ")
    t = re.sub(r"(해\s*주세요|해줘|구해줘|계산해줘|계산해|계산|구해|알려|하게|하기|해|히|줘|주세요)", " ", t)
    t = re.sub(r"(\S+?)\s*부터\s*(\S+?)\s*까지", r"from \1 to \2", t)
    t = t.replace("무한대", "oo").replace("무한", "oo")
    t = re.sub(r"(를|을|는|은|가|이|의|에서|에게|에|으로|로)(?=\s|$)", " ", t)
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
        if not user:
            continue
        print("bot> " + bot.respond(user) + "\n")
        if re.search(r"\b(bye|quit|exit)\b|종료|나가", user, re.IGNORECASE):
            break


if __name__ == "__main__":
    main()
