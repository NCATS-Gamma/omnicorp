# [PubTator3](https://www.ncbi.nlm.nih.gov/research/pubtator3/) makes all its annotations for all of PubMed publicly
# accessible via FTP at https://ftp.ncbi.nlm.nih.gov/pub/lu/PubTator3/.
#
# This Python package is intended to allow for:
# - Downloading all the annotations from PubTator3
# - Loading them into a DuckDB database so that they can be normalized, analyzed and cleaned.

import ftplib
import os
import urllib.parse
import logging

import click


def download_pubtator3(to_dir: str = ".",
                       pubtator3_ftp_url: str = "ftp://ftp.ncbi.nlm.nih.gov/pub/lu/PubTator3/",
                       xml_only: bool = True,
                       ftp_username: str = "anonymous",
                       ftp_password: str = ""):
    """
    Download PubTator3 files from pubtator3_ftp_url to to_dir.

    :param xml_only: Only download the XML files (since PubTator3's FTP directory doesn't have XML in the extension
        name, we're really looking for files containing "XML" somewhere in their filename).
    """

    # Parse the provided FTP URL.
    ftp_url = urllib.parse.urlparse(pubtator3_ftp_url)
    if ftp_url.username:
        ftp_username = ftp_url.username
    if ftp_url.password:
        ftp_password = ftp_url.password

    # Open an FTP connection.
    conn = ftplib.FTP(host=ftp_url.hostname, user=ftp_username, passwd=ftp_password)
    conn.cwd(ftp_url.path)

    # Create directory if not present.
    os.makedirs(to_dir, exist_ok=True)
    logging.debug(f"Created directory {to_dir}.")

    # Get the list of files to download.
    files = conn.mlsd('', ['size'])
    download_count = 0
    for file in files:
        filename = file[0]
        if xml_only and "XML" not in file[0]:
            continue

        download_count += 1

        file_size = file[1]['size']

        logging.info(f"Downloading {filename} ({file_size} bytes)")
        local_file_path = f"{to_dir}/{filename}"
        with open(local_file_path, "wb") as f:
            conn.retrbinary(f"RETR {filename}", f.write)
    
        local_file_size = os.path.getsize(local_file_path)
        logging.info(f"Downloaded {filename} ({local_file_size} bytes on disk)")

    logging.info(f"Downloaded {download_count} files.")


@click.group()
def pubtator3_loader():
    pass

@pubtator3_loader.command()
@click.option("--to", "to_dir", type=click.Path(dir_okay=True, file_okay=False), default=".",
              help="Directory to download PubTator3 files to.")
@click.option("--pubtator3-ftp-url", type=str, default="ftp://ftp.ncbi.nlm.nih.gov/pub/lu/PubTator3/",)
@click.option("--ftp-username", type=str, default="anonymous",
              help="FTP username.")
@click.option("--ftp-password", type=str, default="",
              help="FTP password.")
def download(to_dir, pubtator3_ftp_url, ftp_username, ftp_password):
    """
    Download PubTator3 files from pubtator3_ftp_url to to_dir.
    """
    logging.basicConfig(level=logging.INFO)

    logging.info(f"Downloading PubTator3 from {pubtator3_ftp_url} into {to_dir}.")
    download_pubtator3(to_dir=to_dir,
                       pubtator3_ftp_url=pubtator3_ftp_url,
                       ftp_username=ftp_username,
                       ftp_password=ftp_password)
    pass


if __name__ == "__main__":
    pubtator3_loader()