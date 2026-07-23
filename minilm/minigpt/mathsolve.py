"""A symbolic math engine — real, correct answers to high-school and up math.

The neural model can't do reliable arithmetic, so — exactly like production
agents that call a calculator or run code — math is handled by a genuine
symbolic engine (SymPy). This solves equations and systems, differentiates,
integrates (definite and indefinite), takes limits, simplifies/factors/expands,
computes Taylor series, sums series, and does basic linear algebra, with exact
results.

Usage:
    solve_math("solve x^2 - 5x + 6 = 0")
    solve_math("integral of x^2 from 0 to 1")
    solve_math("limit of sin(x)/x as x -> 0")
    solve_math("d/dx sin(x)*x^2")
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

import sympy as sp
from sympy.parsing.sympy_parser import (
    convert_xor,
    implicit_multiplication_application,
    parse_expr,
    standard_transformations,
)

_TRANSFORMS = standard_transformations + (
    implicit_multiplication_application,  # "2x" -> 2*x, "x(x+1)" -> x*(x+1)
    convert_xor,                          # "x^2" -> x**2
)

# a friendly local namespace so common names resolve as expected
_LOCALS = {
    "pi": sp.pi, "e": sp.E, "E": sp.E, "oo": sp.oo, "inf": sp.oo,
    "infinity": sp.oo, "I": sp.I, "gamma": sp.gamma,
}


class MathError(Exception):
    pass


@dataclass
class MathResult:
    query: str
    kind: str
    result: object              # a SymPy object (or list)
    text: str                   # human-readable answer
    steps: list[str] = field(default_factory=list)

    def __str__(self) -> str:
        return self.text


def _parse(expr: str):
    expr = expr.strip().strip(".")
    if not expr:
        raise MathError("empty expression")
    try:
        return parse_expr(expr, transformations=_TRANSFORMS, local_dict=_LOCALS)
    except Exception as exc:  # noqa: BLE001
        raise MathError(f"could not parse: {expr!r} ({exc})") from exc


def _pick_var(expr, prefer="x"):
    syms = sorted(expr.free_symbols, key=lambda s: s.name)
    if not syms:
        return sp.Symbol(prefer)
    for s in syms:
        if s.name == prefer:
            return s
    return syms[0]


def _fmt(obj) -> str:
    try:
        return sp.pretty(obj, use_unicode=True)
    except Exception:  # noqa: BLE001
        return str(obj)


# --- individual operations ---------------------------------------------------

def _do_solve(body: str, query: str) -> MathResult:
    # split a system on commas / semicolons / newlines
    parts = [p for p in re.split(r"[;\n,]+", body) if p.strip()]
    eqs = []
    for part in parts:
        if "=" in part:
            lhs, rhs = part.split("=", 1)
            eqs.append(sp.Eq(_parse(lhs), _parse(rhs)))
        else:
            eqs.append(sp.Eq(_parse(part), 0))
    syms = sorted({s for eq in eqs for s in eq.free_symbols}, key=lambda s: s.name)
    sol = sp.solve(eqs, syms, dict=True)
    if not sol:
        text = "no solution"
    elif len(eqs) == 1 and len(syms) == 1:
        vals = [s[syms[0]] for s in sol]
        text = f"{syms[0]} = " + ", ".join(sp.sstr(v) for v in vals)
    else:
        text = "; ".join(
            ", ".join(f"{k} = {sp.sstr(v)}" for k, v in s.items()) for s in sol
        )
    return MathResult(query, "solve", sol, text)


def _do_diff(body: str, query: str) -> MathResult:
    wrt = None
    m = re.search(r"(?:with respect to|wrt|d/d([a-zA-Z]))\s*([a-zA-Z])?", body)
    if m:
        wrt = m.group(1) or m.group(2)
        body = re.sub(r"(with respect to|wrt)\s*[a-zA-Z]", "", body)
    expr = _parse(re.sub(r"^d/d[a-zA-Z]\s*", "", body))
    var = sp.Symbol(wrt) if wrt else _pick_var(expr)
    d = sp.diff(expr, var)
    return MathResult(query, "diff", d, f"d/d{var} [{sp.sstr(expr)}] = {sp.sstr(sp.simplify(d))}")


def _do_integrate(body: str, query: str) -> MathResult:
    bounds = re.search(r"from\s+(.+?)\s+to\s+(.+)$", body)
    if bounds:
        a, b = _parse(bounds.group(1)), _parse(bounds.group(2))
        body = body[: bounds.start()]
        expr = _parse(body)
        var = _pick_var(expr)
        val = sp.integrate(expr, (var, a, b))
        return MathResult(query, "integrate_def", val,
                          f"∫[{sp.sstr(a)}..{sp.sstr(b)}] {sp.sstr(expr)} d{var} = {sp.sstr(val)}")
    expr = _parse(body)
    var = _pick_var(expr)
    val = sp.integrate(expr, var)
    return MathResult(query, "integrate", val,
                      f"∫ {sp.sstr(expr)} d{var} = {sp.sstr(val)} + C")


def _do_limit(body: str, query: str) -> MathResult:
    m = re.search(r"as\s+([a-zA-Z])\s*(?:->|→|to)\s*(.+)$", body)
    point, var = sp.Integer(0), None
    if m:
        var = sp.Symbol(m.group(1))
        point = _parse(m.group(2))
        body = body[: m.start()]
    else:
        m2 = re.search(r"\bat\s+(.+)$", body)
        if m2:
            point = _parse(m2.group(1))
            body = body[: m2.start()]
    expr = _parse(body)
    if var is None:
        var = _pick_var(expr)
    val = sp.limit(expr, var, point)
    return MathResult(query, "limit", val,
                      f"lim({var}→{sp.sstr(point)}) {sp.sstr(expr)} = {sp.sstr(val)}")


def _do_unary(op: str, body: str, query: str) -> MathResult:
    expr = _parse(body)
    fn = {"simplify": sp.simplify, "factor": sp.factor, "expand": sp.expand}[op]
    out = fn(expr)
    return MathResult(query, op, out, f"{op}({sp.sstr(expr)}) = {sp.sstr(out)}")


def _do_series(body: str, query: str) -> MathResult:
    n = 6
    m = re.search(r"to\s+order\s+(\d+)", body)
    if m:
        n = int(m.group(1))
        body = body[: m.start()]
    expr = _parse(body)
    var = _pick_var(expr)
    s = expr.series(var, 0, n)
    return MathResult(query, "series", s, f"{sp.sstr(expr)} ≈ {sp.sstr(s)}")


def _do_sum(body: str, query: str) -> MathResult:
    m = re.search(r"for\s+([a-zA-Z])\s*=\s*(.+?)\s+to\s+(.+)$", body)
    if not m:
        raise MathError("summation needs 'for k = a to b'")
    k = sp.Symbol(m.group(1))
    a, b = _parse(m.group(2)), _parse(m.group(3))
    expr = _parse(body[: m.start()])
    val = sp.summation(expr, (k, a, b))
    return MathResult(query, "sum", val,
                      f"Σ({k}={sp.sstr(a)}..{sp.sstr(b)}) {sp.sstr(expr)} = {sp.sstr(val)}")


def _do_eval(body: str, query: str) -> MathResult:
    expr = _parse(body)
    exact = sp.simplify(expr)
    if exact.free_symbols:
        return MathResult(query, "expr", exact, sp.sstr(exact))
    text = sp.sstr(exact)
    # only show a decimal approximation for irrational values (sqrt, pi, ...)
    if exact.is_number and not exact.is_rational:
        text += f"  ≈ {sp.sstr(sp.N(exact, 12))}"
    return MathResult(query, "eval", exact, text)


# --- dispatch ----------------------------------------------------------------

_KEYWORDS = [
    (r"^\s*(solve|roots?( of)?)\b", _do_solve),
    (r"^\s*(differentiate|derivative( of)?|diff|d/d[a-zA-Z])\b", _do_diff),
    (r"^\s*(integrate|integral( of)?|antiderivative( of)?)\b", _do_integrate),
    (r"^\s*(limit( of)?|lim)\b", _do_limit),
    (r"^\s*(simplify)\b", lambda b, q: _do_unary("simplify", b, q)),
    (r"^\s*(factor(i[sz]e)?)\b", lambda b, q: _do_unary("factor", b, q)),
    (r"^\s*(expand)\b", lambda b, q: _do_unary("expand", b, q)),
    (r"^\s*(taylor|series)( of)?\b", _do_series),
    (r"^\s*(sum|summation)( of)?\b", _do_sum),
]


def solve_math(query: str) -> MathResult:
    """Parse a math request and return an exact answer via SymPy."""
    q = query.strip()
    if not q:
        raise MathError("empty query")
    for pattern, handler in _KEYWORDS:
        m = re.match(pattern, q, flags=re.IGNORECASE)
        if m:
            body = q[m.end():].strip()
            return handler(body, query)
    # no keyword: an equation to solve, or an expression to evaluate
    if "=" in q and not re.search(r"[<>]=|==", q):
        return _do_solve(q, query)
    return _do_eval(q, query)


def looks_like_math(text: str) -> bool:
    """Heuristic: does this message want the math engine?"""
    t = text.strip().lower()
    if re.match(
        r"^(solve|diff|derivative|integrate|integral|limit|lim|simplify|"
        r"factor|expand|taylor|series|sum|summation|antiderivative|roots?)\b",
        t,
    ):
        return True
    # bare arithmetic / algebra: has math operators and no prose sentence feel
    if re.search(r"[0-9]\s*[\+\-\*/\^=]\s*|[\+\-\*/\^]\s*[0-9(a-z]|\bsqrt\b|\bx\^", t):
        return len(t) <= 120
    return False
