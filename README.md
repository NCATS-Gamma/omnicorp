[![Build Status](https://travis-ci.org/NCATS-Gamma/omnicorp.svg?branch=master)](https://travis-ci.org/NCATS-Gamma/omnicorp)

# OmniCorp

Extract ontology terms referenced from PubMed abstracts as per the [MEDLINE/PubMed Baseline Repository](https://mbr.nlm.nih.gov/) by using [SciGraph](https://github.com/SciGraph/SciGraph) against a set of ontologies.

# OmniCORD

Extract ontology terms used in the [COVID-19 Open Research Dataset (CORD)](https://www.semanticscholar.org/cord19) as tab-delimited files for further processing in [COVID-KOP](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC7316095/).

In order to generate OmniCORD output files, you should:
1. Use `robocord.job` to attempt to run all the jobs on a SLURM cluster.
   Any number of jobs can be specified, but values of around 4000 seem
   to work with. Example: `sbatch --array=0-3999 robocord.job`.
2. Use RoboCORDManager to re-run any jobs that failed to complete. You can
   use the `--dry-run` option to see what jobs will be executed before they
   are run. Jobs are executed using the `robocord-sbatch.sh` script, so
   modify that if necessary.
   Example: `srun sbt "runMain org.renci.robocord.RoboCORDManager --job-size 20`

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
