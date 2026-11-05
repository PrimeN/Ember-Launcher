#!/usr/bin/env python3
"""Print the visual acceptance worksheet; all cases start unverified."""
import csv
import itertools
import sys

ACCENTS = ("Ember", "Shadow Ember", "Blue ember", "Green flame", "Rose ember", "Modern Ember", "Cinema red")

PLAYERS = ("Basic", "Ember Modern", "Ember X")

def cases():
    for accent, theme, interface, player, artwork, text, effects in itertools.product(
        ACCENTS, ("Day", "Night", "OLED"), ("Modern", "Light", "Ember X"), PLAYERS,
        ("Bright", "Dark/missing"), ("Standard", "Large"), ("Fast", "Balanced", "Cinematic")):
        yield (accent, theme, interface, player, artwork, text, effects, "PENDING")

if __name__ == "__main__":
    writer = csv.writer(sys.stdout)
    writer.writerow(("accent", "theme", "interface", "player", "artwork", "text", "effects", "result"))
    writer.writerows(cases())
