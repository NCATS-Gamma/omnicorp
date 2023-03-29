from src.postprocess.Normalize import normalize
from subprocess import call

def go():
    normalize('tsv-output','omnicorp_output',pmidcol=0,termcol=1,labelcol=None)
    call('sort -T . omnicorp_output/annotation_0.txt | uniq > omnicorp_output/annotation_1.txt', shell=True)
    curify('omnicorp_output/annotation_1.txt', 'omnicorp_output/annotation_2.txt')
    make_final('omnicorp_output','omnicorp_final')

def curify(infname,outfname):
    with open(infname,'r') as inf, open(outfname,'w') as outf:
        for line in inf:
            x = line.strip().split('\t')
            pmid = x[1].split('/')[-1]
            outf.write(f'{x[0]}\tPMID:{pmid}\n')

def make_final(indir,outdir):
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
