#!/usr/bin/env python3
"""Verify a Sudoku solution from stdin. Each line should be 9 space-separated digits."""
import sys

def verify(grid):
    errors = []
    for i in range(9):
        row = [grid[i][j] for j in range(9)]
        if sorted(row) != list(range(1, 10)):
            errors.append(f"Row {i+1}: {row}")
    for j in range(9):
        col = [grid[i][j] for i in range(9)]
        if sorted(col) != list(range(1, 10)):
            errors.append(f"Col {j+1}: {col}")
    for br in range(3):
        for bc in range(3):
            box = [grid[br*3+r][bc*3+c] for r in range(3) for c in range(3)]
            if sorted(box) != list(range(1, 10)):
                errors.append(f"Box ({br+1},{bc+1}): {box}")
    return errors

grid = []
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    nums = [int(x) for x in line.split()]
    if len(nums) == 9:
        grid.append(nums)

if len(grid) != 9:
    print(f"ERROR: Expected 9 rows, got {len(grid)}")
    sys.exit(1)

errors = verify(grid)
if errors:
    print(f"INVALID — {len(errors)} errors:")
    for e in errors:
        print(f"  {e}")
    sys.exit(1)
else:
    print("VALID — correct Sudoku solution!")
