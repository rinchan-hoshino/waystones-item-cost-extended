#!/usr/bin/env python3
"""Exercise native payments and teleports in isolated Minecraft servers."""
import argparse,json,os,signal,shutil,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def run(profile,output,eula,alias=None,lightman=False,subject=None):
    mc=alias or profile['minecraft'];loader=profile['loader'];case=mc+'-'+loader
    instance=output/case;instance.mkdir(parents=True,exist_ok=True)
    shutil.copy2(eula,instance/'eula.txt');(instance/'config').mkdir(exist_ok=True)
    (instance/'config/waystones-item-cost-extended.properties').write_text('recipePayments='+('false' if lightman else 'true')+'\n')
    (instance/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25918\nonline-mode=false\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nmax-tick-time=0\n')
    marker=instance/'item-cost-runtime.pass';marker.unlink(missing_ok=True)
    if subject:
        (instance/'mods').mkdir(exist_ok=True)
        shutil.copy2(subject, instance/'mods'/subject.name)
    env=os.environ.copy();env['JAVA_HOME']=env['JAVA_21_HOME']
    toolchains=','.join(v for k,v in env.items() if k.startswith('JAVA_') and k.endswith('_HOME'))
    common=['-PrinlibDir='+str(ROOT/'build/rinlib'),'-PprofileDependenciesDir='+str(ROOT/'build/profile-dependencies'),
        '-Dprobe.sources='+str(ROOT/'runtime-tests'),'-Dprobe.checks='+str(ROOT/'runtime-tests'/('checks-lightman-1.21.1' if lightman else 'checks-'+profile['generation'])),
        '-Dprobe.actions='+('none' if profile['generation']=='shogi' else 'context' if mc in ['1.21.10','1.21.11'] else 'player'),'-Dprobe.instance='+str(instance),'-Dprobe.players='+('26-neoforge' if mc.startswith('26.') else '1.20.4' if mc=='1.20.4' else 'modern'),
        '-Porg.gradle.java.installations.paths='+toolchains,'--no-daemon','--no-configuration-cache','--max-workers=2']
    if lightman:
        common.append('-Dprobe.extraMod='+str(ROOT/'build/profile-dependencies'/('lightmanscurrency-'+profile['id']+'.jar')))
    if subject:
        common.append('-Dprobe.subject='+str(subject.resolve()))
    if loader=='neoforge':
        cmd=[str(ROOT/'gradlew'),'-Pprofile='+profile['id'],'-I',str(ROOT/'runtime-tests/neoforge.init.gradle'),':neoforge-modern:runProbeServer']+common
    else:
        old=mc in ['1.20.4','1.20.6','1.21']
        wrapper=ROOT/'gradlew' if old else ROOT/'tools/gradle-9.2.1/gradlew'
        cmd=[str(wrapper),'-p',str(ROOT/'fabric'),'-I',str(ROOT/'runtime-tests/fabric.init.gradle'),'runProbeServer',
            '-Pminecraft_version='+mc,'-PdependencyMinecraft='+profile['minecraft'],'-PprofileId='+profile['id'],
            '-Ploom_version='+('1.11.8' if old else '1.14.10'),'-Ploader_version=0.19.3',
            '-Pfabric_api_version='+profile['fabric_api'],'-Psource_generation='+profile['generation'],'-Pjava_version='+str(profile['java']),
            '-Pwaystones_version='+profile['waystones']['version_number'],'-Pbalm_version='+profile['balm']['version_number'],
            '-Pgame_versions_json='+json.dumps(profile['game_versions'])]+common
    log=instance/'run.log'
    with log.open('w') as stream:
        proc=subprocess.Popen(cmd,cwd=ROOT,env=env,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
        try:code=proc.wait(timeout=900)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid,signal.SIGTERM)
            try:proc.wait(timeout=10)
            except subprocess.TimeoutExpired:os.killpg(proc.pid,signal.SIGKILL);proc.wait()
            code=-1
    text=log.read_text(errors='replace')
    passed=code==0 and marker.exists() and 'ITEM_COST_RUNTIME_PASS' in text and 'ITEM_COST_RUNTIME_FAIL' not in text
    result={'minecraft':mc,'loader':loader,'profile':profile['id'],'passed':passed,'exit_code':code,'assertions':text.count('ITEM_COST_ASSERT_PASS'),'log':str(log)}
    (instance/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(case+': '+('PASS' if passed else 'FAIL'),flush=True)
    return passed

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--profile',action='append');parser.add_argument('--alias');parser.add_argument('--fabric-api');parser.add_argument('--lightman',action='store_true');parser.add_argument('--subject',type=Path);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--eula',type=Path,required=True);args=parser.parse_args()
    if 'eula=true' not in args.eula.read_text():parser.error('Use an existing accepted EULA file')
    profiles=json.loads((ROOT/'support/profiles.json').read_text())['profiles']
    selected=[p for p in profiles if p['id'] in args.profile] if args.profile else [p for p in profiles if (p['minecraft'] in ['1.20.4','1.20.6'] and p['loader']=='fabric') or (p['minecraft'] not in ['1.20.4','1.20.6'] and p['loader']=='neoforge')]
    if not selected:parser.error('No matching profiles')
    if args.lightman and any(p['id'] != '1.21.1-neoforge' for p in selected):parser.error('Lightman fixture requires --profile 1.21.1-neoforge')
    if args.subject and (len(selected) != 1 or selected[0]['loader'] != 'neoforge'):parser.error('Final-JAR fixture requires one NeoForge profile')
    if args.fabric_api:
        selected=[dict(p, fabric_api=args.fabric_api) for p in selected]
    results=[run(p,args.output.resolve(),args.eula.resolve(),args.alias,args.lightman,args.subject) for p in selected]
    raise SystemExit(0 if all(results) else 1)
if __name__=='__main__':main()
