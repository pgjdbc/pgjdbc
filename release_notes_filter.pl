#!/usr/bin/env perl
use strict;
use utf8;
use open qw(:std :utf8);

use WWW::Curl::Easy;
use JSON;
use JSON::Parse 'json_file_to_perl';
use Unicode::Collate;

my $version = shift;

my $contributors = json_file_to_perl('contributors.json');
my $con_count = keys %$contributors;

sub save_contributors {
  if ($con_count == keys %$contributors) {
    return;
  }
  my $fh;
  open $fh, ">", "contributors.json";
  print $fh JSON->new->pretty->canonical->encode($contributors);
  close $fh;
}

my %author_url;

my $fetch = sub {
    my $curl = WWW::Curl::Easy->new();
    my ( $header, $body );
    $curl->setopt( CURLOPT_URL,            shift );
    $curl->setopt( CURLOPT_WRITEHEADER,    \$header );
    $curl->setopt( CURLOPT_WRITEDATA,      \$body );
    $curl->setopt( CURLOPT_FOLLOWLOCATION, 1 );
    $curl->setopt( CURLOPT_TIMEOUT,        10 );
    $curl->setopt( CURLOPT_SSL_VERIFYPEER, 1 );
    $curl->setopt( CURLOPT_USERAGENT, "Awesome-Pgjdbc-App");
    $curl->perform;
    {
        header => $header,
        body   => $body,
        info   => $curl->getinfo(CURLINFO_HTTP_CODE),
        error  => $curl->errbuf,
    };
};

sub authorUrl {
  my $sha = (shift);
  my $res = $fetch->("https://api.github.com/repos/pgjdbc/pgjdbc/commits/$sha");
  if ($res->{info} != 200) {
    return
  }
  my $json = decode_json($res->{body});
  my $author = $json->{author};
  my $url = $author->{html_url};
  if ($url) {
    return $url;
  }
  return $json->{commit}->{author}->{email};
}

my %authors;
my $currentAuthor;

while(<>) {
  if ($_ !~ /@@@/) {
    print $_;
    if ($_ =~ /(.*) \(\d+\):/) {
      $currentAuthor = $1;
      $authors{$currentAuthor} = 1;
      print "\n";
    }
    next;
  }
  my @c = split('@@@', $_);
  my $subject = @c[0];
  my $sha = @c[1];
  my $shortSha = @c[2];

  if (!$contributors->{$currentAuthor}) {
    $contributors->{$currentAuthor} = authorUrl($sha);
  }

  my $pr = '';
  # PR id can be either "... (#42)" or just "... #42"
  if ($subject =~ /\(?#(\d+)\)?/) {
    $subject =~ s;\(?#(\d+)\)?;[PR \1](https://github.com/pgjdbc/pgjdbc/pull/\1);;
  } else {
    my $body = `git log --format='%B' -n 1 $sha`;

    if ($body =~ /(?:fix|fixes|close|closes) *#?(\d+)/) {
      $pr = $1;
    }
  }
  if ($pr != '') {
    $pr = ' [PR '.$pr.'](https://github.com/pgjdbc/pgjdbc/pull/'.$pr.')';
  }
  $subject =~ s/^\s+/* /;

  print $subject.$pr." [".$shortSha."](https://github.com/pgjdbc/pgjdbc/commit/$sha)\n";
}

print "<a name=\"contributors_{{ page.version }}\"></a>\n";
print "### Contributors to this release\n\n";

print "We thank the following people for their contributions to this release.\n\n";
for my $c (Unicode::Collate->new(level => 2)->sort(keys(%authors))) {
  my $url = $contributors->{$c};
  if ($url) {
    print "[$c]($url)";
  } else {
    print $c;
  }
  print "  \n"
}

save_contributors();
