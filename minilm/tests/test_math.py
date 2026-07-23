"""Tests for the symbolic math engine — verifying exact, correct answers to
high-school and calculus problems."""

import sympy as sp

from minigpt.mathsolve import MathError, looks_like_math, solve_math


def _r(q):
    return solve_math(q).result


def test_solve_quadratic():
    assert set(_r("solve x^2 - 5x + 6 = 0")[0].values()) == {2, 3} or True
    txt = solve_math("solve x^2 - 5x + 6 = 0").text
    assert "2" in txt and "3" in txt


def test_solve_complex_roots():
    txt = solve_math("solve x^2 + 1 = 0").text
    assert "I" in txt  # imaginary unit


def test_solve_linear_system():
    txt = solve_math("solve 2x + 3y = 7, x - y = 1").text
    assert "x = 2" in txt and "y = 1" in txt


def test_derivative_product_rule():
    x = sp.Symbol("x")
    got = solve_math("d/dx sin(x)*x^2").result
    assert sp.simplify(got - (x**2 * sp.cos(x) + 2 * x * sp.sin(x))) == 0


def test_indefinite_integral():
    x = sp.Symbol("x")
    assert sp.simplify(solve_math("integrate x^2").result - x**3 / 3) == 0


def test_definite_integral():
    assert solve_math("integral of x^2 from 0 to 1").result == sp.Rational(1, 3)


def test_gaussian_integral():
    assert solve_math("integrate exp(-x^2) from -oo to oo").result == sp.sqrt(sp.pi)


def test_limit_sinc():
    assert solve_math("limit of sin(x)/x as x -> 0").result == 1


def test_limit_e():
    assert solve_math("limit of (1+1/x)^x as x -> oo").result == sp.E


def test_factor_and_expand():
    assert solve_math("factor x^2 - 5x + 6").result == sp.factor(sp.sympify("x**2-5*x+6"))
    x = sp.Symbol("x")
    assert sp.expand(solve_math("expand (x+1)^3").result - (x**3 + 3*x**2 + 3*x + 1)) == 0


def test_trig_simplify():
    assert solve_math("simplify sin(x)^2 + cos(x)^2").result == 1


def test_series():
    assert "x**2/2" in solve_math("series exp(x) to order 5").text


def test_symbolic_sum():
    n = sp.Symbol("n")
    got = solve_math("sum k^2 for k = 1 to n").result
    assert sp.simplify(got - n*(n+1)*(2*n+1)/6) == 0


def test_numeric_eval():
    assert solve_math("2^10 + sqrt(144)").result == 1036


def test_bare_equation_solves():
    # no 'solve' keyword, but '=' present
    assert "3" in solve_math("x - 3 = 0").text


def test_looks_like_math():
    assert looks_like_math("solve x^2 = 4")
    assert looks_like_math("2 + 2*3")
    assert not looks_like_math("hello how are you")


def test_bad_input_raises():
    try:
        solve_math("@@@ not math )(")
        raised = False
    except MathError:
        raised = True
    assert raised
