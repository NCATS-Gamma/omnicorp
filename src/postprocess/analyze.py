import itertools
from collections import defaultdict

# Read in a two column tab delimited file where the first column is a curie and the second colum is a pmid
# Produce a file containing each curie and the number of pmids it is associated with
# Produce a file contining each pmid and the number of curies it is associated with
# Count the number of unique curie pairs that share a pmid


def run(infilename):
    curie_to_pmids = defaultdict(set)
    pmid_to_curies = defaultdict(set)
    with open(infilename, "r") as inf:
        for line in inf:
            curie, pmid = line.strip().split("\t")
            curie_to_pmids[curie].add(pmid)
            pmid_to_curies[pmid].add(curie)
    with open("curie_to_pmids.txt", "w") as outf:
        for curie, pmids in curie_to_pmids.items():
            outf.write(f"{curie}\t{len(pmids)}\n")
    with open("pmid_to_curies.txt", "w") as outf:
        for pmid, curies in pmid_to_curies.items():
            outf.write(f"{pmid}\t{len(curies)}\n")
    curie_pairs = set()
    for pmid, curies in pmid_to_curies.items():
        for curie1, curie2 in itertools.combinations(sorted(curies), 2):
            curie_pairs.add((curie1, curie2))
    print(f"number of unique curie pairs: {len(curie_pairs)}")

if __name__ == '__main__':
    run("omnicorp_output/annotation.txt")