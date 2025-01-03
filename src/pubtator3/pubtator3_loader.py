# [PubTator3](https://www.ncbi.nlm.nih.gov/research/pubtator3/) makes all its annotations for all of PubMed publicly
# accessible via FTP at https://ftp.ncbi.nlm.nih.gov/pub/lu/PubTator3/.
#
# This Python package is intended to allow for:
# - Downloading all the annotations from PubTator3
# - Loading them into a DuckDB database so that they can be normalized, analyzed and cleaned.

import os
import urllib.parse
import subprocess
import logging

import click


def download_pubtator3(to_dir: str = ".",
                       pubtator3_ftp_url: str = "ftp://ftp.ncbi.nlm.nih.gov/pub/lu/PubTator3/",
                       xml_only: bool = True):
    """
    Download PubTator3 files from pubtator3_ftp_url to to_dir. Requires wget (https://www.gnu.org/software/wget/).

    :param xml_only: Only download the XML files (since PubTator3's FTP directory doesn't have XML in the extension
        name, we're really looking for files containing "XML" somewhere in their filename).
    """

    # Parse the provided FTP URL.
    ftp_url = urllib.parse.urlparse(pubtator3_ftp_url)
    if ftp_url.username:
        ftp_username = ftp_url.username
    if ftp_url.password:
        ftp_password = ftp_url.password

    # Set up a wget command line to download the specified files.
    wget_command_line = [
        'wget',
        '--progress=bar:force:noscroll',
        '-c',           # Continue incomplete downloads.
        '-r',           # Turn on recursion.
        '-l1',          # Recurse one level.
        '-nd',          # No directories -- only save files.
        '-np',          # No parents -- don't ascend into parent directories.
        '-P', to_dir    # Set directory prefix to {to_dir}.
    ]
    if xml_only:
        wget_command_line.extend(['-A', '*XML*'])

    wget_command_line.append(pubtator3_ftp_url)

    logging.info(f"Downloading PubTator3 files using wget command: {wget_command_line}")
    process = subprocess.run(wget_command_line)
    if process.returncode != 0:
        raise Exception(f"wget command failed with return code {process.returncode}: {process.stderr}")


@click.group()
def pubtator3_loader():
    pass

@pubtator3_loader.command()
@click.option("--to", "to_dir", type=click.Path(dir_okay=True, file_okay=False), default=".",
              help="Directory to download PubTator3 files to.")
@click.option("--pubtator3-ftp-url", type=str, default="ftp://anonymous:@ftp.ncbi.nlm.nih.gov/pub/lu/PubTator3/",)
def download(to_dir, pubtator3_ftp_url):
    """
    Download PubTator3 files from pubtator3_ftp_url to to_dir.
    """
    logging.basicConfig(level=logging.INFO)

    logging.info(f"Downloading PubTator3 from {pubtator3_ftp_url} into {to_dir}.")
    download_pubtator3(to_dir=to_dir,
                       pubtator3_ftp_url=pubtator3_ftp_url,)
    pass


if __name__ == "__main__":
    pubtator3_loader()