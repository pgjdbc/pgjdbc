#!/bin/perl
use strict;

my $version = shift;

my %author_url = (
  'AlexElin' => 'https://github.com/AlexElin',
  'aryabukhin' => 'https://github.com/aryabukhin',
  'bd-infor' => 'https://github.com/bd-infor',
  'Christian Ullrich' => 'https://github.com/chrullrich',
  'Christopher Deckers' => 'https://github.com/Chrriis',
  'Daniel Gustafsson' => 'https://github.com/danielgustafsson',
  'Dave Cramer' => 'davec@postgresintl.com',
  'Eric McCormack' => 'https://github.com/ericmack',
  'Florin Asăvoaie' => 'https://github.com/FlorinAsavoaie',
  'George Kankava' => 'https://github.com/georgekankava',
  'goeland86' => 'https://github.com/goeland86',
  'Jeremy Whiting' => 'https://github.com/whitingjr',
  'Jordan Lewis' => 'https://github.com/jordanlewis',
  'Jorge Solorzano' => 'https://github.com/jorsol',
  'Laurenz Albe' => 'https://github.com/laurenz',
  'Marc Petzold' => 'https://github.com/dosimeta',
  'Marios Trivyzas' => 'https://github.com/matriv',
  'Mathias Fußenegger' => 'https://github.com/mfussenegger',
  'Minglei Tu' => 'https://github.com/tminglei',
  'Pavel Raiskup' => 'https://github.com/praiskup',
  'Petro Semeniuk' => 'https://github.com/PetroSemeniuk',
  'Philippe Marschall' => 'https://github.com/marschall',
  'Philippe Marschall' => 'https://github.com/marschall',
  'Rikard Pavelic' => 'https://github.com/zapov',
  'Roman Ivanov' => 'https://github.com/romani',
  'Sebastian Utz' => 'https://github.com/seut',
  'Steve Ungerer' => 'https://github.com/scubasau',
  'Tanya Gordeeva' => 'https://github.com/tmgordeeva',
  'Trygve Laugstøl' => 'https://github.com/trygvis',
  'Vladimir Gordiychuk' => 'https://github.com/Gordiychuk',
  'Vladimir Sitnikov' => 'https://github.com/vlsi',
);


my %authors;

while(<>) {
  if ($_ !~ /@@@/) {
    print $_;
    if ($_ =~ /(.*) \(\d+\):/) {
      $authors{$1} = 1;
      print "\n";
    }
    next;
  }
  my @c = split('@@@', $_);
  my $subject = @c[0];
  my $sha = @c[1];
  my $shortSha = @c[2];

  my $pr = '';
  if ($subject =~ /\(#(\d+)\)/) {
    $subject =~ s;\(#(\d+)\);[PR#\1](https://github.com/pgjdbc/pgjdbc/pull/\1);;
  } else {
    my $body = `git log --format='%B' -n 1 $sha`;

    if ($body =~ /(?:fix|fixes|close|closes) *#?(\d+)/) {
      $pr = $1;
    }
  }
  if ($pr != '') {
    $pr = ' [PR#'.$pr.'](https://github.com/pgjdbc/pgjdbc/pull/'.$pr.')';
  }
  $subject =~ s/^\s+/* /;

  print $subject.$pr." [".$shortSha."](https://github.com/pgjdbc/pgjdbc/commit/$sha)\n";
}

print "<a name=\"contributors_$version\"></a>\n";
print "### Contributors to this release\n\n";

print "We thank the following people for their contributions to this release.\n\n";
for my $c (sort keys(%authors)) {
  if ($author_url{$c}) {
    print "[$c](".$author_url{$c}.")";
  } else {
    print $c;
  }
  print "  \n"
}
