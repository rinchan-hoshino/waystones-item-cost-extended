from pathlib import Path
from PIL import Image, ImageDraw
R=Path(__file__).resolve().parent
S=4
im=Image.new('RGB',(480*S,480*S),'white'); d=ImageDraw.Draw(im)
ink='#36362f'
shapes=[]
def poly(points, fill, outline=True):
    shapes.append((points,fill,outline))
    p=[(int(x*S),int(y*S)) for x,y in points]
    d.polygon(p,fill=fill)
    if outline: d.line(p+[p[0]],fill=ink,width=10*S,joint='curve')
# Original full-size straight-line illustration. Upper-left lighting.
# Stone and plinth share the same front center and depth direction.
poly([(140,380),(290,380),(320,360),(170,360)],'#c8c4ad')
poly([(290,380),(320,360),(320,400),(290,420)],'#737769')
poly([(140,380),(290,380),(290,420),(140,420)],'#a6a994')
# Narrow side and top planes give the simple slab its depth.
poly([(270,220),(300,200),(300,370),(270,390)],'#737769')
poly([(160,220),(190,200),(300,200),(270,220)],'#ddd5b8')
poly([(160,220),(270,220),(270,390),(160,390)],'#b6b8a3')
poly([(170,230),(180,230),(180,380),(170,380)],'#d9d5bc',False)
poly([(250,230),(260,230),(260,380),(250,380)],'#959b88',False)
# One clear activation inset, without decorative stray strokes.
poly([(210,270),(230,290),(230,330),(210,350),(190,330),(190,290)],'#65556b')
poly([(210,280),(220,295),(220,325),(210,340),(200,325),(200,295)],'#c9a2df',False)
poly([(200,295),(210,280),(210,330),(200,320)],'#edd0f0',False)
# Floating emerald: symmetric silhouette, a few solid facets without internal ink.
gem=[(125,75),(145,75),(165,85),(175,105),(175,135),(165,155),(145,165),(125,165),(105,155),(95,135),(95,105),(105,85)]
poly(gem,'#39b96f')
poly([(120,80),(145,80),(165,110),(110,110)],'#9aebaa',False)
poly([(100,115),(110,110),(125,145),(120,155),(100,128)],'#65d88e',False)
poly([(165,112),(170,115),(170,128),(147,160),(123,160),(145,139)],'#218456',False)
# Restore the regular isometric cube proportions from the earlier sketch.
poly([(295,65),(355,95),(295,125),(235,95)],'#9aebaa')
poly([(235,95),(295,125),(295,185),(235,155)],'#39b96f')
poly([(295,125),(355,95),(355,155),(295,185)],'#218456')
# Full-resolution master, followed by true 48px reduction and palette cleanup.
master=im.resize((480,480),Image.Resampling.LANCZOS);master.save(R/'colored-source.png')
# Sample the unblurred full-size drawing at pixel centers; preserve exact solid colors.
small=im.resize((48,48),Image.Resampling.NEAREST)
# Clean the rounded silhouette and its highlights on the final pixel grid.
p=ImageDraw.Draw(small)
p.rectangle((8,6,19,18), fill='white')
gem=[(12,7),(15,7),(17,9),(18,11),(18,13),(17,15),(15,17),(12,17),(10,15),(9,13),(9,11),(10,9)]
p.polygon(gem,fill=ink)
p.polygon([(12,8),(15,8),(16,9),(17,11),(17,13),(16,15),(15,16),(12,16),(11,15),(10,13),(10,11),(11,9)],fill='#39b96f')
p.polygon([(12,8),(15,8),(16,10),(12,10),(11,12),(10,12),(10,11),(11,9)],fill='#9aebaa')
p.polygon([(17,11),(17,13),(16,15),(15,16),(12,16),(13,14),(15,14)],fill='#218456')
# Single-pixel cube edges preserve the three planes and equal side widths.
p.rectangle((22,5,36,19), fill='white')
for pts,fill in [([(29,6),(35,9),(29,12),(23,9)],'#9aebaa'), ([(23,9),(29,12),(29,18),(23,15)],'#39b96f'), ([(29,12),(35,9),(35,15),(29,18)],'#218456')]:
    p.polygon(pts,fill=fill)
    p.line(pts+[pts[0]],fill=ink,width=1)
# Match the slab's existing depth-edge staircase exactly: 22,21,20,20.
# Translate that edge to the plinth, then duplicate it three rows below.
p.rectangle((29,38,33,42),fill='white')
p.rectangle((31,35,33,42),fill='white')
for x, top in zip(range(29,33), (38,37,36,36)):
    p.line([(x,top),(x,top+3)],fill='#737769',width=1)
    p.point((x,top),fill=ink)
    p.point((x,top+3),fill=ink)
p.line([(29,38),(29,41)],fill=ink,width=1)
p.line([(32,36),(32,39)],fill=ink,width=1)
small.save(R/'icon-48.png')
small.resize((576,576),Image.Resampling.NEAREST).save(R/'icon-576.png')
svg=['<svg xmlns="http://www.w3.org/2000/svg" width="480" height="480" viewBox="0 0 480 480">','<rect width="480" height="480" fill="white"/>']
for pts,c,o in shapes:
    p=' '.join(f'{x},{y}' for x,y in pts)
    svg.append(f'<polygon points="{p}" fill="{c}" stroke="{ink if o else "none"}" stroke-width="10" stroke-linejoin="round"/>')
svg.append('</svg>');(R/'colored-source.svg').write_text('\n'.join(svg))
assert small.size==(48,48)
print(R/'icon-576.png')
