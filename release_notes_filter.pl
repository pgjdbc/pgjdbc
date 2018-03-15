#!/bin/perl
use strict;

my $version = shift;

my %author_url = (
  'Alexander Kjäll' => 'https://github.com/alexanderkjall',
  'AlexElin' => 'https://github.com/AlexElin',
  'Álvaro Hernández Tortosa' => 'https://github.com/ahachete',
  'aryabukhin' => 'https://github.com/aryabukhin',
  'Barnabas Bodnar' => 'https://github.com/bbodnar',
  'bd-infor' => 'https://github.com/bd-infor',
  'bpd0018' => 'https://github.com/bpd0018',
  'Brett Okken' => 'https://github.com/bokken',
  'Brett Wooldridge' => 'https://github.com/brettwooldridge',
  'chalda' => 'https://github.com/ochaloup',
  'Chen Huajun' => 'https://github.com/ChenHuajun',
  'Christian Ullrich' => 'https://github.com/chrullrich',
  'Christopher Deckers' => 'https://github.com/Chrriis',
  'Daniel Gustafsson' => 'https://github.com/danielgustafsson',
  'Daniel Migowski' =>'https://github.com/dmigowski',
  'Dave Cramer' => 'davec@postgresintl.com',
  'djydewang' => 'https://github.com/djydewang',
  'eperez' => 'https://github.com/eperez',
  'Eric McCormack' => 'https://github.com/ericmack',
  'Florin Asăvoaie' => 'https://github.com/FlorinAsavoaie',
  'George Kankava' => 'https://github.com/georgekankava',
  'goeland86' => 'https://github.com/goeland86',
  'Hugh Cole-Baker' => 'https://github.com/sigmaris',
  'Jacques Fuentes' => 'https://github.com/jpfuentes2',
  'James' => 'https://github.com/jamesthomp',
  'Jamie Pullar' => 'https://github.com/JamiePullar',
  'JCzogalla' => 'https://github.com/JCzogalla',
  'Jeff Klukas' => 'https://github.com/jklukas',
  'Jeremy Whiting' => 'https://github.com/whitingjr',
  'Joe Kutner' => 'https://github.com/jkutner',
  'Jordan Lewis' => 'https://github.com/jordanlewis',
  'Jorge Solorzano' => 'https://github.com/jorsol',
  'Laurenz Albe' => 'https://github.com/laurenz',
  'Magnus Hagander' => 'https://github.com/mhagander',
  'Magnus' => 'https://github.com/magJ',
  'Marc Petzold' => 'https://github.com/dosimeta',
  'Marios Trivyzas' => 'https://github.com/matriv',
  'Mathias Fußenegger' => 'https://github.com/mfussenegger',
  'Michael Glaesemann' => 'https://github.com/grzm',
  'MichaelZg' => 'https://github.com/michaelzg',
  'Minglei Tu' => 'https://github.com/tminglei',
  'mjanczykowski' => 'https://github.com/mjanczykowski',
  'Pavel Raiskup' => 'https://github.com/praiskup',
  'Pawel' => 'https://github.com/veselov',
  'Petro Semeniuk' => 'https://github.com/PetroSemeniuk',
  'Philippe Marschall' => 'https://github.com/marschall',
  'Piyush Sharma' => 'https://github.com/ps-sp',
  'Rikard Pavelic' => 'https://github.com/zapov',
  'rnveach' => 'https://github.com/rnveach',
  'Robert Zenz' => 'https://github.com/RobertZenz',
  'Robert \'Bobby\' Zenz' => 'https://github.com/RobertZenz',
  'Roman Ivanov' => 'https://github.com/romani',
  'Sebastian Utz' => 'https://github.com/seut',
  'Sehrope Sarkuni' => 'https://github.com/sehrope',
  'Selene Feigl' => 'https://github.com/sfeigl',
  'Simon Stelling' => 'https://github.com/stellingsimon',
  'slmsbrhgn' => 'https://github.com/slmsbrhgn',
  'Steve Ungerer' => 'https://github.com/scubasau',
  'Tanya Gordeeva' => 'https://github.com/tmgordeeva',
  'Thach Hoang' => 'https://github.com/thachhoang',
  'trtrmitya' => 'https://github.com/trtrmitya',
  'Trygve Laugstøl' => 'https://github.com/trygvis',
  'Vladimir Gordiychuk' => 'https://github.com/Gordiychuk',
  'Vladimir Sitnikov' => 'https://github.com/vlsi',
  'zapov' => 'https://github.com/zapov',
  'Zemian Deng' => 'https://github.com/zemian',
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

print "<a name=\"contributors_{{ page.version }}\"></a>\n";
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
