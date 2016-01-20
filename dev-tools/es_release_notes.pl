#!/usr/bin/env perl
# Licensed to Elasticsearch under one or more contributor
# license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright
# ownership. Elasticsearch licenses this file to you under
# the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance  with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on
# an 'AS IS' BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied. See the License for the specific
# language governing permissions and limitations under the License.

use strict;
use warnings;

use HTTP::Tiny;
use IO::Socket::SSL 1.52;
<<<<<<< HEAD

my $Base_URL  = 'https://api.github.com/repos/';
my $User_Repo = 'elasticsearch/elasticsearch/';
my $Issue_URL = "http://github.com/${User_Repo}issues/issue/";

my @Groups       = qw(breaking feature enhancement bug regression doc test);
my %Group_Labels = (
    breaking    => 'Breaking changes',
=======
use utf8;

my $Github_Key = load_github_key();
my $Base_URL   = "https://${Github_Key}api.github.com/repos/";
my $User_Repo  = 'elastic/elasticsearch/';
my $Issue_URL  = "http://github.com/${User_Repo}issues/";

my @Groups = qw(
    breaking deprecation feature
    enhancement bug regression upgrade build doc test
);
my %Group_Labels = (
    breaking    => 'Breaking changes',
    build       => 'Build',
    deprecation => 'Deprecations',
>>>>>>> v2.1.1
    doc         => 'Docs',
    feature     => 'New features',
    enhancement => 'Enhancements',
    bug         => 'Bug fixes',
<<<<<<< HEAD
    regression  => 'Regression',
    test        => 'Tests',
    other       => 'Not classified',
=======
    regression  => 'Regressions',
    test        => 'Tests',
    upgrade     => 'Upgrades',
    other       => 'NOT CLASSIFIED',
>>>>>>> v2.1.1
);

use JSON();
use Encode qw(encode_utf8);

my $json = JSON->new->utf8(1);

my %All_Labels = fetch_labels();

my $version = shift @ARGV
    or dump_labels();

dump_labels("Unknown version '$version'")
    unless $All_Labels{$version};

<<<<<<< HEAD
my $format = shift @ARGV || "html";

=======
>>>>>>> v2.1.1
my $issues = fetch_issues($version);
dump_issues( $version, $issues );

#===================================
sub dump_issues {
#===================================
    my $version = shift;
    my $issues  = shift;

    $version =~ s/v//;
    my ( $day, $month, $year ) = (gmtime)[ 3 .. 5 ];
    $month++;
    $year += 1900;

<<<<<<< HEAD
    for my $group ( @Groups, 'other' ) {
        my $group_issues = $issues->{$group} or next;
        $format eq 'html' and print "<h2>$Group_Labels{$group}</h2>\n\n<ul>\n";
        $format eq 'markdown' and print "## $Group_Labels{$group}\n\n";

        for my $header ( sort keys %$group_issues ) {
            my $header_issues = $group_issues->{$header};
            my $prefix        = "<li>";
            if ($format eq 'html') {
                if ( $header && @$header_issues > 1 ) {
                    print "<li>$header:<ul>";
                    $prefix = "<li>";
                }
                elsif ($header) {
                    $prefix = "<li>$header: ";
                }
            }
            for my $issue (@$header_issues) {
                my $title = $issue->{title};
                if ( $issue->{state} eq 'open' ) {
                    $title .= " [OPEN]";
                }
                my $number = $issue->{number};
                $format eq 'markdown' and print encode_utf8( "* "
                        . $title
                        . qq( [#$number](${Issue_URL}${number})\n)
                );
                $format eq 'html' and print encode_utf8( $prefix
                        . $title
                        . qq[ <a href="${Issue_URL}${number}">#${number}</a></li>\n]
                );
            }
            if ($format eq 'html' && $header && @$header_issues > 1 ) {
                print "</li></ul></li>\n";
            }
        }
        $format eq 'html' and print "</ul>";
        print "\n\n"
=======
    print <<"ASCIIDOC";
:issue: https://github.com/${User_Repo}issues/
:pull:  https://github.com/${User_Repo}pull/

[[release-notes-$version]]
== $version Release Notes

ASCIIDOC

    for my $group ( @Groups, 'other' ) {
        my $group_issues = $issues->{$group} or next;
        print "[[$group-$version]]\n"
            . "[float]\n"
            . "=== $Group_Labels{$group}\n\n";

        for my $header ( sort keys %$group_issues ) {
            my $header_issues = $group_issues->{$header};
            print( $header || 'HEADER MISSING', "::\n" );

            for my $issue (@$header_issues) {
                my $title = $issue->{title};

                if ( $issue->{state} eq 'open' ) {
                    $title .= " [OPEN]";
                }
                unless ( $issue->{pull_request} ) {
                    $title .= " [ISSUE]";
                }
                my $number = $issue->{number};

                print encode_utf8("* $title {pull}${number}[#${number}]");

                if ( my $related = $issue->{related_issues} ) {
                    my %uniq = map { $_ => 1 } @$related;
                    print keys %uniq > 1
                        ? " (issues: "
                        : " (issue: ";
                    print join ", ", map {"{issue}${_}[#${_}]"}
                        sort keys %uniq;
                    print ")";
                }
                print "\n";
            }
            print "\n";
        }
        print "\n\n";
>>>>>>> v2.1.1
    }
}

#===================================
sub fetch_issues {
#===================================
    my $version = shift;
    my @issues;
<<<<<<< HEAD
=======
    my %seen;
>>>>>>> v2.1.1
    for my $state ( 'open', 'closed' ) {
        my $page = 1;
        while (1) {
            my $tranche
                = fetch( $User_Repo
                    . 'issues?labels='
                    . $version
                    . '&pagesize=100&state='
                    . $state
                    . '&page='
                    . $page )
                or die "Couldn't fetch issues for version '$version'";
<<<<<<< HEAD
            last unless @$tranche;
            push @issues, @$tranche;
            $page++;
=======
            push @issues, @$tranche;

            for my $issue (@$tranche) {
                next unless $issue->{pull_request};
                for ( $issue->{body} =~ m{(?:#|${User_Repo}issues/)(\d+)}g ) {
                    $seen{$_}++;
                    push @{ $issue->{related_issues} }, $_;
                }
            }
            $page++;
            last unless @$tranche;
>>>>>>> v2.1.1
        }
    }

    my %group;
ISSUE:
    for my $issue (@issues) {
<<<<<<< HEAD
        my %labels = map { $_->{name} => 1 } @{ $issue->{labels} };
        my $header = $issue->{title} =~ s/^([^:]+):\s+// ? $1 : '';
=======
        next if $seen{ $issue->{number} } && !$issue->{pull_request};
        my %labels = map { $_->{name} => 1 } @{ $issue->{labels} };
        my ($header) = map { substr( $_, 1 ) } grep {/^:/} keys %labels;
        $header ||= 'NOT CLASSIFIED';
>>>>>>> v2.1.1
        for (@Groups) {
            if ( $labels{$_} ) {
                push @{ $group{$_}{$header} }, $issue;
                next ISSUE;
            }
        }
        push @{ $group{other}{$header} }, $issue;
    }

    return \%group;
}

#===================================
sub fetch_labels {
#===================================
    my %all;
    my $page = 1;
    while (1) {
        my $labels = fetch( $User_Repo . 'labels?page=' . $page++ )
            or die "Couldn't retrieve version labels";
        last unless @$labels;
        for (@$labels) {
            my $name = $_->{name};
            next unless $name =~ /^v/;
            $all{$name} = 1;
        }
    }
    return %all;
}

#===================================
sub fetch {
#===================================
    my $url      = $Base_URL . shift();
    my $response = HTTP::Tiny->new->get($url);
    die "$response->{status} $response->{reason}\n"
        unless $response->{success};

    #    print $response->{content};
    return $json->decode( $response->{content} );
}

#===================================
<<<<<<< HEAD
=======
sub load_github_key {
#===================================
    my ($file) = glob("~/.github_auth");
    unless ( -e $file ) {
        warn "File ~/.github_auth doesn't exist - using anonymous API. "
            . "Generate a Personal Access Token at https://github.com/settings/applications\n";
        return '';
    }
    open my $fh, $file or die "Couldn't open $file: $!";
    my ($key) = <$fh> || die "Couldn't read $file: $!";
    $key =~ s/^\s+//;
    $key =~ s/\s+$//;
    die "Invalid GitHub key: $key"
        unless $key =~ /^[0-9a-f]{40}$/;
    return "$key:x-oauth-basic@";

}

#===================================
>>>>>>> v2.1.1
sub dump_labels {
#===================================
    my $error = shift || '';
    if ($error) {
        $error = "\nERROR: $error\n";
    }
    my $labels = join( "\n     - ", '', ( sort keys %All_Labels ) );
    die <<USAGE
    $error
    USAGE: $0 version > outfile

    Known versions:$labels

USAGE

}
