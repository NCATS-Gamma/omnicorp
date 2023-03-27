[![Build Status](https://travis-ci.org/NCATS-Gamma/omnicorp.svg?branch=master)](https://travis-ci.org/NCATS-Gamma/omnicorp)

# OmniCorp

Extract ontology terms referenced from PubMed abstracts as per the [MEDLINE/PubMed Baseline Repository](https://mbr.nlm.nih.gov/) by using [SciGraph](https://github.com/SciGraph/SciGraph) against a set of ontologies.

## Prerequisites

Using OmniCorp requires the following open source tools:
- Git
- Maven
- Scala and sbt
- wget

On macOS, these can be installed [using Homebrew](https://brew.sh/) by running
the command: `brew install git maven scala sbt wget`.

## Setting up SciGraph

We need to use a [specially modified version of SciGraph](https://github.com/gaurav/SciGraph/tree/public-constructors-and-methods) in order to carry out text annotations.

To install this version locally, run `make SciGraph`. This will download, compile and install the customized SciGraph we use.

You will then need to run `make omnicorp-scigraph` to generate the SciGraph instance for the ontologies specified in ontologies.ofn.

## Retrieving data and running OmniCorp

`make pubmed-annual-baseline/done` will retrieve the pubmed abstracts.   

Running the annotation does not use the makefile.  Instead, take a look at the number of ttl files downloaded into pubmed-annual-baseline, and submit annotation jobs using slurm:

`sbatch --array=1-N omnicorp.job`  where N is the number of ttl files.

# Ontologies used
Currently, we look for terms from the following ontologies:
* [Uberon (base)](http://uberon.org) ([OWL](http://purl.obolibrary.org/obo/uberon/uberon-base.owl))
* [ChEBI](https://www.ebi.ac.uk/chebi/) ([OWL](http://purl.obolibrary.org/obo/chebi.owl))
* [Cell Ontology](http://www.obofoundry.org/ontology/cl.html) ([OWL](http://purl.obolibrary.org/obo/cl.owl))
* [Environment Ontology](https://github.com/EnvironmentOntology/envo) ([OWL](http://purl.obolibrary.org/obo/envo.owl))
* [Gene Ontology (plus)](http://www.obofoundry.org/ontology/go.html) ([OWL](http://purl.obolibrary.org/obo/go/extensions/go-plus.owl))
* [NCBITaxon](http://www.obofoundry.org/ontology/ncbitaxon.html) ([OWL](http://purl.obolibrary.org/obo/ncbitaxon.owl))
* [Relation Ontology](http://www.obofoundry.org/ontology/ro.html) ([OWL](http://purl.obolibrary.org/obo/ro.owl))
* [PRotein Ontology (PRO)](http://www.obofoundry.org/ontology/pr.html) ([OWL](http://purl.obolibrary.org/obo/pr.owl))
* [Biological Spatial Ontology](http://www.obofoundry.org/ontology/bspo.html) ([OWL](http://purl.obolibrary.org/obo/bspo.owl))
* [Mondo Disease Ontology](http://obofoundry.org/ontology/mondo.html) ([OWL](http://purl.obolibrary.org/obo/mondo.owl))
* [The Human Phenotype Ontology](https://hpo.jax.org/) ([OWL](http://purl.obolibrary.org/obo/hp.owl))
* [Ontology for Biomedical Investigations](http://purl.obolibrary.org/obo/obi) ([OWL](http://purl.obolibrary.org/obo/obi.owl))
* [Sequence Ontology](https://github.com/The-Sequence-Ontology/SO-Ontologies) ([OWL](http://purl.obolibrary.org/obo/so.owl))
* [HUGO Gene Nomenclature Committee](https://www.genenames.org/) ([OWL](https://data.monarchinitiative.org/ttl/hgnc.ttl))
* [Experimental Factor Ontology](https://www.ebi.ac.uk/efo/) ([OWL](http://www.ebi.ac.uk/efo/efo.owl))
