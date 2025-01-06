# [PubTator3](https://www.ncbi.nlm.nih.gov/research/pubtator3/) makes all its annotations for all of PubMed publicly
# accessible via FTP at https://ftp.ncbi.nlm.nih.gov/pub/lu/PubTator3/.
#
# This Python package is intended to allow for:
# - Downloading all the annotations from PubTator3
# - Loading them into a DuckDB database so that they can be normalized, analyzed and cleaned.



import re
import tarfile
import urllib.parse
import subprocess
import logging

import click
import duckdb
import bioc
from bioc import biocxml

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


@pubtator3_loader.command()
@click.argument("biocxml_tar_gz_filename", type=click.Path(exists=True, dir_okay=False, file_okay=True), nargs=-1)
@click.option("--duckdb", "duckdb_filename", type=click.Path(dir_okay=False, file_okay=True), help="The DuckDB file to write to. If none is provided, ")
@click.option("--check-only", is_flag=True, default=False, help="Don't load the individual BioCXML files, just check if the entire BioCXML file can be read.")
@click.option("--source", type=str, help="The source to use in the database.")
def load(biocxml_tar_gz_filename: str, duckdb_filename: str, check_only=False, source=None):
    """
    Load the BioCXML.tar.gz file(s) into the DuckDB database.
    """
    logging.basicConfig(level=logging.INFO)

    # Make sure we have at least one input file.
    if len(biocxml_tar_gz_filename) == 0:
        raise RuntimeError("At least one BioCXML.tar.gz file must be provided.")

    # If no DuckDB filename is provided, we replace the .tar.gz extension with .duckdb.
    if duckdb_filename is None:
        duckdb_filename = re.sub(r"(?i)\.tar\.gz$", "", biocxml_tar_gz_filename) + ".duckdb"

    # Set up DuckDB.
    db = duckdb.connect(duckdb_filename)

    # Turn on a progress bar.
    db.sql("PRAGMA enable_progress_bar=true")

    # Create databases if they don't already exist.
    db.sql("""CREATE TABLE IF NOT EXISTS Texts (
        Source TEXT,
        DocumentID TEXT NOT NULL,
        SectionIndex LONG,
        SectionTitle TEXT,
        BodyText TEXT,
        AnnotatedBy TEXT[]
    );""")
    db.sql("""CREATE TABLE IF NOT EXISTS Annotations (
        DocumentID TEXT NOT NULL,
        SectionIndex LONG,
        SectionTitle TEXT,
        AnnotationEngine TEXT,
        StartIndex LONG,
        EndIndex LONG,
        Text TEXT,
        ExpectedText TEXT,
        ConceptID TEXT,
        ConceptType TEXT
    );""")

    document_count = 0
    biocxml_count = 0
    biocxmlgz_count = 0
    for filename in biocxml_tar_gz_filename:
        # If we don't have a source, use the filename.
        if source is None:
            pubtator3_source = biocxml_tar_gz_filename
        else:
            pubtator3_source = source

        biocxmlgz_count += 1
        logging.info(f"Loading BioCXML.tar.gz file {filename} into a DuckDB database at {duckdb_filename}.")

        with tarfile.open(filename, "r:gz") as tf:
            for member in tf:
                logging.debug(f"Checking BioCXML member {member}.")
                if member.name.lower().endswith(".bioc.xml"):
                    with tf.extractfile(member) as biocxmlf:
                        biocxml_count += 1

                        with biocxml.iterparse(biocxmlf) as reader:
                            collection_info = reader.get_collection_info()
                            logging.info(f"Loaded BioCXML file {member.name} with collection: {collection_info}.")

                            if check_only:
                                continue

                            annotation_count = 0
                            for document in reader:
                                document_count += 1

                                pmid = f"PMID:{document.id}"
                                for passage_index, passage in enumerate(document.passages):
                                    passage_text = passage.text

                                    db.execute("INSERT INTO Texts VALUES (?, ?, ?, ?, ?, ?)", [
                                        str(passage.infons),
                                        pmid,
                                        passage_index,
                                        "",
                                        passage.text,
                                        [pubtator3_source]
                                    ])

                                    for annotation in passage.annotations:
                                        for location in annotation.locations:
                                            annotation_count += 1

                                            # Tweak some identifiers
                                            concept_id = annotation.infons.get("identifier", "")
                                            concept_type = annotation.infons.get("type", "")

                                            if concept_type == 'Species' and concept_id is not None and concept_id.isdigit():
                                                concept_id = f"NCBITaxon:{concept_id}"

                                            if concept_type == 'Gene' and concept_id is not None and concept_id.isdigit():
                                                concept_id = f"NCBIGene:{concept_id}"

                                            # Let's make sure the offsets are correct.
                                            start_index = location.offset - passage.offset
                                            end_index = start_index + location.length - 1
                                            expected_text = passage_text[start_index:(end_index + 1)]

                                            db.execute("INSERT INTO Annotations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", [
                                                pmid,
                                                passage_index,
                                                "",
                                                pubtator3_source,
                                                start_index,
                                                end_index,
                                                annotation.text,
                                                expected_text,
                                                concept_id,
                                                concept_type,
                                            ])

                            # After every file in the tar.gz file, write everything to the DuckDB database.
                            db.commit()
                            logging.info(f"Loaded {annotation_count} annotations from BioCXML file {member.name}.")

    logging.info(f"Loaded {document_count} documents in {biocxml_count} BioCXML files from {biocxmlgz_count} BioCXML.tar.gz files.")
    db.close()

if __name__ == "__main__":
    pubtator3_loader()
