#!/bin/bash

# All arguments are passed on to the RoboCORD instance.

sbatch <<EOT
#!/bin/bash
#
#SBATCH --job-name=RoboCORD
#SBATCH --output=robocord-output/log-output-%A.txt
#SBATCH --error=robocord-output/log-error-%A.txt
#SBATCH --cpus-per-task 16
#SBATCH --mem=50000
#SBATCH --time=12:00:00
#SBATCH --mail-user=gaurav@renci.org

set -e # Exit immediately if a pipeline fails.

export JAVA_OPTS="-Xmx50G"
export MY_SCIGRAPH=omnicorp-scigraph-\$SLURM_JOB_ID

echo "Duplicating omnicorp-scigraph so we can use it on multiple clusters"
cp -R omnicorp-scigraph "scigraphs/\$MY_SCIGRAPH"

echo "Starting RoboCORD with arguments: --neo4j-location scigraphs/\$MY_SCIGRAPH $@"
sbt "runMain org.renci.robocord.RoboCORD --neo4j-location scigraphs/\$MY_SCIGRAPH $@"

rm -rf "scigraphs/\$MY_SCIGRAPH"
echo "Deleted duplicated omnicorp-scigraph"
EOT
