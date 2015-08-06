#!/bin/perl

while(<>) {
  if ($_ !~ /@@@/) {
    print $_;
    next;
  }
  my @c = split('@@@', $_);
  my $subject = @c[0];
  my $sha = @c[1];
  my $shortSha = @c[2];
  my $body = `git log --format='%B' -n 1 $sha`;
  my $pr = '';
  if ($body =~ /(?:fix|fixes|close|closes) *#?(\d+)/) {
    $pr = ' PR #'.$1;
  }
  print $subject.$pr." (".$shortSha.")\n";
}
