#!/usr/bin/perl
# This script converts kml ZCTA definitions to sql in the format:
# (<zip_code>, <coords>)
# e.g. : (90210, [[-118.439626 34.115428] .. [-118.439626 34.115428]])
#
# where there is a table named 'zcta' with the following structure:
#
#| Field       | Type   | Collation       | Attributes        | Null | Default |
#| zip         | int(5) |                 | UNSIGNED ZEROFILL | No   | None    |
#| coordinates | text   | utf8_general_ci |                   | No   | None    |
# 
#
# This script uses data from:
# http://www2.census.gov/geo/tiger/GENZ2014/kml/cb_2014_us_zcta510_500k.zip
#
# Usage: sql_from_base_kml.pl <filename>
#    ex: sql_from_base_kml.pl cb_2014_us_zcta510_500k.kml > zcta.sql
$file_name = $ARGV[0];
open(FILE,$file_name);

@file = <FILE>;
$db = join('',@file);

my @sql;

# extract the coordinates and create sql for each zip
while ($db =~ m/(<Placemark((\s|\w|\W)+?)<\/Placemark>)/g)
{
    my $zcta;
    $placemark = $1;
    $1 =~  m/name="ZCTA5CE10">(\d+)/;
    $zcta = "($1, '[";
    my @latlng;
    while ($placemark =~ m/<coordinates>((-|\d|[.]|,|\s)+?)<\/coordinates>/g)
    {
	my @path;
	my @rawcoords = split /\s+/,$1;
	foreach (@rawcoords)
	{
	    my @coords = split /,/,$_;
	    push @path,"[$coords[1] $coords[0]]";
	}
	$zcta .= "[";
	foreach (@path)
	{
	    $zcta .= $_;
	}
	$zcta .= "]";
    }
    $zcta .= "]')";
    push @sql, $zcta;
}

#Create a INSERT sql command for all zips
print "INSERT INTO `zctas` (`zip`,`coordinates`) VALUES\n";

for ($i = 0; $i <= $#sql; $i++) {
    print $sql[$i];
    if ($i != $#sql) {
	print ",\n";
    } else {
	print ";\n";
    }
}

