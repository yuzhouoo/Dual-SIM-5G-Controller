/* Read-only Qualcomm QRTR/QMI DMS Get Band Capabilities probe.
 * No vendor libraries, no diag/NV requests, no modem reset.
 * Build: NDK clang --target=aarch64-linux-android31 -O2 -Wall -o dms_probe qmi_dms_probe.c
 */
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifndef AF_QIPCRTR
#define AF_QIPCRTR 42
#endif
#ifndef QRTR_PORT_CTRL
#define QRTR_PORT_CTRL 0xfffffffeu
#endif
struct sockaddr_qrtr_local { sa_family_t sq_family; uint32_t sq_node; uint32_t sq_port; };
static unsigned rd16(const unsigned char *p){return (unsigned)p[0]|((unsigned)p[1]<<8);}
static void hex_dump(const unsigned char *p, size_t n){for(size_t i=0;i<n;i++)printf("%02X",p[i]);}
static void list16(const unsigned char *p, size_t n, const char *prefix){
 if(n<2){printf("  invalid array (len=%zu)\n",n);return;}
 unsigned count=rd16(p);
 if(count>(n-2)/2){printf("  malformed array count=%u len=%zu\n",count,n);return;}
 printf("  count=%u; ",count);
 for(unsigned i=0;i<count;i++)printf("%s%u%s",prefix,rd16(p+2+i*2),i+1<count?", ":"\n");
}
int main(void){
 setvbuf(stdout,NULL,_IONBF,0);
 printf("DMS QRTR read-only probe; service=2 node=0 port=119 msg=0x0045\n");
 int fd=socket(AF_QIPCRTR,SOCK_DGRAM,0);
 if(fd<0){printf("ERROR socket errno=%d %s\n",errno,strerror(errno));return 10;}
 struct timeval tv={.tv_sec=3,.tv_usec=0};
 setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&tv,sizeof(tv));
 setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&tv,sizeof(tv));
 /* Do not bind with sq_node=0: on some Android kernels the local QRTR
  * node is not 0, and an explicit bind then fails with EINVAL. The kernel
  * auto-binds an ephemeral local port on first sendto(). */
 struct sockaddr_qrtr_local local={0};
 socklen_t local_len=sizeof(local);
 if(getsockname(fd,(struct sockaddr*)&local,&local_len)==0)
   printf("Local QRTR socket pre-send: node=%u port=%u\n",local.sq_node,local.sq_port);
 else
   printf("INFO getsockname pre-send errno=%d %s\n",errno,strerror(errno));
 struct sockaddr_qrtr_local dst={.sq_family=AF_QIPCRTR,.sq_node=0,.sq_port=119};
 const unsigned char msg[]={0x00,0x01,0x00,0x45,0x00,0x00,0x00};
 if(sendto(fd,msg,sizeof(msg),0,(struct sockaddr*)&dst,sizeof(dst))!=(ssize_t)sizeof(msg)){
   printf("ERROR sendto errno=%d %s\n",errno,strerror(errno));close(fd);return 12;
 }
 local_len=sizeof(local);
 if(getsockname(fd,(struct sockaddr*)&local,&local_len)==0)
   printf("Local QRTR auto-bound: node=%u port=%u\n",local.sq_node,local.sq_port);
 unsigned char buf[4096]; struct sockaddr_qrtr_local peer; socklen_t peerlen=sizeof(peer);
 ssize_t nr=recvfrom(fd,buf,sizeof(buf),0,(struct sockaddr*)&peer,&peerlen);close(fd);
 if(nr<0){printf("ERROR recvfrom errno=%d %s\n",errno,strerror(errno));return 13;}
 printf("Received %zd bytes from node=%u port=%u\n",nr,peer.sq_node,peer.sq_port);
 if(nr<7){puts("ERROR short QMI header");return 14;}
 printf("QMI type=%u transaction=%u msgId=0x%04X payloadLen=%u\n",buf[0],rd16(buf+1),rd16(buf+3),rd16(buf+5));
 if(buf[0]!=2||rd16(buf+1)!=1||rd16(buf+3)!=0x0045){puts("ERROR unexpected response (not expected DMS response)");return 15;}
 size_t end=7u+rd16(buf+5);if(end>(size_t)nr){puts("ERROR truncated QMI response");return 16;}
 int resultSeen=0,successful=0,anyBand=0;
 for(size_t pos=7;pos+3<=end;){
   unsigned type=buf[pos];unsigned n=rd16(buf+pos+1);pos+=3;
   if(n>end-pos){puts("ERROR malformed TLV length");return 17;}
   const unsigned char *v=buf+pos;
   if(type==0x02){
     if(n>=4){unsigned result=rd16(v),error=rd16(v+2);printf("QMI result=%u error=%u\n",result,error);resultSeen=1;successful=(result==0);}
   } else if(type==0x01){
     printf("Legacy band mask: ");hex_dump(v,n);puts("");anyBand=1;
   } else if(type==0x10){
     printf("LTE 64-bit band mask: ");hex_dump(v,n);puts("");
     if(n==8){uint64_t bits=0;for(int i=0;i<8;i++)bits|=(uint64_t)v[i]<<(8*i);
       printf("  LTE B bands (legacy mask): ");int shown=0;
       for(unsigned b=1;b<=64;b++)if(bits & ((uint64_t)1<<(b-1))){printf("B%u ",b);shown++;}
       printf("(%d shown)\n",shown);}
     anyBand=1;
   } else if(type==0x12){printf("Extended LTE bands: ");list16(v,n,"B");anyBand=1;
   } else if(type==0x13){printf("NR5G bands: ");list16(v,n,"N");anyBand=1;
   } else {printf("Other TLV 0x%02X (%u bytes)\n",type,n);}
   pos+=n;
 }
 if(!resultSeen){puts("WARNING result TLV absent; firmware-specific response");return 18;}
 if(!successful){puts("QUERY_FAILED modem returned a QMI error");return 19;}
 if(!anyBand){puts("QUERY_OK_NO_BANDS: no known band capability TLVs returned");return 20;}
 puts("QUERY_OK: bands above are modem-reported; may not equal enabled bands or RF front-end availability");
 return 0;
}
