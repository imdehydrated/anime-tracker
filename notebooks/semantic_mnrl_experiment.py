"""Compatibility wrapper.

Phase 7 pivoted from MNRL-only training to a multi-positive hard-neighbor setup.
Use `semantic_multipos_experiment.py` directly for new runs.
"""

from semantic_multipos_experiment import main


if __name__ == "__main__":
    print(
        "[deprecated] semantic_mnrl_experiment.py now forwards to "
        "semantic_multipos_experiment.py"
    )
    main()
