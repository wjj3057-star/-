# Training results and generated samples

Actual output from this repo's model — a 3.25M-parameter char-level GPT trained from random init on 7.2 MB of Python stdlib source, 8000 steps (~45 min on a 4-core CPU).

## Learning curve (validation loss)

Cross-entropy in nats/char; bits/char = nats / ln(2). Random init on a 216-symbol vocab starts near **7.75 bits/char**.

| step | train (nats) | val (nats) | val (bits/char) |
|---|---|---|---|
| 0 | 5.095 | 5.110 | 7.37 |
| 500 | 1.984 | 1.539 | 2.22 |
| 1500 | 1.283 | 0.706 | 1.02 |
| 2500 | 1.093 | 0.518 | 0.75 |
| 3500 | 0.978 | 0.396 | 0.57 |
| 4500 | 0.898 | 0.335 | 0.48 |
| 5500 | 0.876 | 0.297 | 0.43 |
| 6500 | 0.819 | 0.272 | 0.39 |
| 7500 | 0.804 | 0.256 | 0.37 |
| 8000 | 0.790 | 0.254 | 0.37 |

**Best val loss: 0.252 nats ≈ 0.36 bits/char** (from ~7.75 at random init).

## Generated code (temperature 0.8, top-k 40)

### prompt: `'def '`

```python
def space_declnames(self):
        """Returns the method is already declaration.

        If no is not of the following in the space head been python to called in the get an option of package
        'rpm' win32' setup_comma
```

### prompt: `'class '`

```python
class not from code classes.
        """
        for k in self.process_executable.executables():
            out._create_key = self.executable_key
            return self.document

        while self._executable:
            s
```

### prompt: `'import '`

```python
import locations as a backward/encode"

    def __init__(self):
        """Local on the context mode local and locals.  The encoded to stack at the first mode in the context package
        active already when the make data and
```

### prompt: `'    for '`

```python
    for reading in self._conditions:
                if readline is not None:
                    readline = self._mode = self._mode
                elif os.path.exists(self.prefix):
                    try:
                    
```

## Honest read

The samples are convincingly **Python-shaped** — valid `def`/`class` headers, correct indentation, `self`-methods, docstrings, keywords — but not executable programs. That's expected for a tiny char-level model at this scale. It is a real, from-scratch code language model; scale it up (see `GPU.md`) for stronger output.
