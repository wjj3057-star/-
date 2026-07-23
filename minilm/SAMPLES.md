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


---

# Bigger model (10.78M) — partial run, finish on GPU

A larger model (6 layers, 384-wide, 10.78M params) on the expanded 10.4 MB corpus, trained to **step 3000 of 12000** on CPU before handing off to GPU (see `GPU.md` §4b). Loss here is on the harder 10.4 MB corpus, so it is **not** comparable to the small model's numbers above.

| step | train | val (nats) |
|---|---|---|
| 0 | 5.224 | 5.254 |
| 250 | 2.252 | 2.309 |
| 500 | 1.730 | 1.833 |
| 750 | 1.409 | 1.529 |
| 1000 | 1.267 | 1.384 |
| 1250 | 1.135 | 1.290 |
| 1500 | 1.055 | 1.230 |
| 1750 | 1.004 | 1.171 |
| 2000 | 0.971 | 1.135 |
| 2250 | 0.923 | 1.111 |
| 2500 | 0.911 | 1.089 |
| 2750 | 0.885 | 1.059 |
| 3000 | 0.867 | 1.049 |

## Samples from the partial big model (step 3000)

### prompt: `'def '`

```python
def section(section):
        """Subsection on copies form stite compiles below a perkle
        number of now opened for perkline, bucklasses.  This no staticod
        # we nopening we about only list f
```

### prompt: `'class '`

```python
class not scheme
        try:
            new_response = {}
    except OSError:
        # Assertion compatible yets
            if new_response else None:
                raise Unknown_responseError("should
```

### prompt: `'    def __init__(self'`

```python
    def __init__(self, seet):
        '''targetterite threads any() callable property for domainsons are completed by
        a target, arglist.
        """
        if self._name is None:
            self._unpack_target(N
```

Even undertrained, it emits real Python signatures verbatim (e.g. `def encode(self, input, errors='strict'):` from the codecs module) and more English-word-coherent identifiers than the small model. Finishing it on a GPU takes minutes and yields a clearly stronger model.
