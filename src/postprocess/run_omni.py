from src.postprocess.Normalize import normalize
from subprocess import call

def go():
    normalize('tsv-output','omnicorp_output',pmidcol=0,termcol=1,labelcol=None)
    #call('sort -T . omnicorp_output/annotation_0.txt | uniq > omnicorp_output/annotation_1.txt', shell=True)
    #separate_by_prefix('omnicorp_output','omnicorp_final')

def separate_by_prefix(indir,outdir):
    """Separate annotations by prefix."""
    prefix=''
    ofile = None
    with open(f'{indir}/annotation_3.txt','r') as inf:
        for line in inf:
            x = line.strip().split('\t')
            cpref = x[0].split(':')[0]
            if cpref != prefix:
                if ofile is not None:
                    ofile.close()
                prefix = cpref
                ofile = open(f'{outdir}/{prefix}','w')
            pmid = x[1]
            ofile.write(f'{pmid}\t{x[0]}\n')
    ofile.close()

if __name__ == '__main__':
    go()
