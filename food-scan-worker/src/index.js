// ForgeFit Food Scan — Cloudflare Worker
// Deploy as a Worker, set OPENAI_API_KEY as an encrypted secret,
// and set ALLOWED_ORIGIN to https://appassets.androidplatform.net
// This endpoint has no user authentication; enable Cloudflare rate limits
// and usage budgets before exposing it publicly.
export default {
 async fetch(request, env) {
  const origin = request.headers.get("Origin") || "";
  const allowed = env.ALLOWED_ORIGIN || "https://appassets.androidplatform.net";
  const headers = {"Access-Control-Allow-Origin":allowed,"Access-Control-Allow-Methods":"POST, OPTIONS","Access-Control-Allow-Headers":"Content-Type","Vary":"Origin","Cache-Control":"no-store"};
  if(request.method==="OPTIONS") return new Response(null,{status:204,headers});
  if(request.method!=="POST") return new Response("Method not allowed",{status:405,headers});
  if(origin!==allowed) return new Response("Origin not allowed",{status:403,headers});
  if(!env.OPENAI_API_KEY) return new Response(JSON.stringify({error:"Service not configured"}),{status:503,headers});
  const size=Number(request.headers.get("Content-Length")||0);
  if(size>3_000_000) return new Response(JSON.stringify({error:"Image too large"}),{status:413,headers});
  try {
   const body=await request.json();
   if(typeof body.image!=="string"||body.image.length>3_000_000||!/^data:image\/(jpeg|png|webp);base64,[a-zA-Z0-9+/=]+$/.test(body.image)) throw new Error("Invalid image");
   const ai=await fetch("https://api.openai.com/v1/responses",{
    method:"POST",headers:{"Authorization":"Bearer "+env.OPENAI_API_KEY,"Content-Type":"application/json"},
    body:JSON.stringify({model:"gpt-4.1-mini",store:false,max_output_tokens:600,
     input:[{role:"user",content:[
      {type:"input_text",text:"Estimate food and portion from this meal image. Return ONLY JSON with keys name (short meal description), serving (estimated portion text), calories (integer), protein (grams), carbs (grams), fat (grams), notes (brief uncertainty). Total meal values. If no food visible return error. Be cautious with hidden oils, sauces and serving sizes. Never imply precision."},
      {type:"input_image",image_url:body.image,detail:"low"}
     ]}]})
   });
   if(!ai.ok) return new Response(JSON.stringify({error:"Food analysis unavailable"}),{status:502,headers:{"Content-Type":"application/json",...headers}});
   const data=await ai.json();
   const output=(data.output||[]).flatMap(x=>x.content||[]).find(x=>x.type==="output_text")?.text||"";
   const obj=JSON.parse(output.replace(/^\s*```(?:json)?/,"").replace(/```\s*$/,"").trim());
   if(obj.error) throw new Error("No recognizable meal");
   for(const k of ["calories","protein","carbs","fat"]){obj[k]=Number(obj[k]);if(!Number.isFinite(obj[k])||obj[k]<0||obj[k]>10000)throw new Error("Invalid estimate")}
   obj.name=String(obj.name||"Food photo").slice(0,120);
   obj.serving=String(obj.serving||"").slice(0,100);
   obj.notes=String(obj.notes||"Photo estimate; review portions").slice(0,240);
   return new Response(JSON.stringify(obj),{headers:{"Content-Type":"application/json",...headers}});
  }catch(e){return new Response(JSON.stringify({error:"Could not estimate this photo. Try another photo or enter food manually."}),{status:422,headers:{"Content-Type":"application/json",...headers}})}
 }
};
