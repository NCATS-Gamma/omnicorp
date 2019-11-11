# FieldSummary

This Scala tools counts all the XML tag names in a set of PubMed archives.

It also includes all possible values for particular attributes, as listed in
the `expandAttributeValues` variable.

## Instructions

Counting the XML tags for an individual XML file is carried out with the
FieldSummary tool:

```$ srun sbt -mem 50000 "runMain FieldSummary ../pubmed-annual-baseline output 16"```

The three arguments here are:
 - The input XML file or directory. As in Omnicorp, if a directory is provided,
   only files with an `.xml.gz` extension will be processed.
 - The output directory. For every input file, an output file with the name
   `$input_filename.fields.txt.gz` will be created, with the format:
     <count>\t<full path to the XML tag, separated with `.`>
 - Number of parallel processes to run simultaneously.

This process can be run in parallel across multiple XML files. See the
[example Slurm script](./fieldsummary.job) to see how this can be executed
using Slurm's [job array support](https://slurm.schedmd.com/job_array.html).

Once output files have been generated for all output files, they can be added up
using the SumOutputs tool:

```$ srun "runMain SumOutputs output"```

This will produce a series of tuples, in the format `(fieldName, count)`.
