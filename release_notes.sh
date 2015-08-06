#!/bin/sh

git shortlog --format="%s@@@%H@@@%h@@@" --no-merges $1 | perl release_notes_filter.pl
