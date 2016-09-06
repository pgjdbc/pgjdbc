#!/bin/perl
use strict;

while(<>) {
  if ($_ !~ /@@@/) {
    print $_;
    if ($_ =~ /:/) {
      print "\n";
    }
    next;
  }
  my @c = split('@@@', $_);
  my $subject = @c[0];
  my $sha = @c[1];
  my $shortSha = @c[2];
  my $body = `git log --format='%B' -n 1 $sha`;
  my $mergeSha = `git log  --reverse --ancestry-path --merges --format=%H $sha..HEAD | head -1`;
  my $mergeSubject = `git log --format=%B -n 1 $mergeSha`;

  my $pr = '';
  if ($body =~ /(?:fix|fixes|close|closes) *#?(\d+)/) {
    $pr = $1;
  }
  if ($pr == '' && $mergeSubject =~ /Merge pull request #(\d+)/) {
    $pr = $1;
  }
  if ($pr != '') {
    $pr = ' [PR#'.$pr.'](https://github.com/pgjdbc/pgjdbc/pull/'.$pr.')';
  }
  $subject =~ s/^\s+/* /;

  print $subject.$pr." [".$shortSha."](https://github.com/pgjdbc/pgjdbc/commit/$sha)\n";
}
