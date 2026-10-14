const MODEL = 'gemini-3.5-flash-lite';
function doPost(e) {
  try {
    const input = JSON.parse(e.postData.contents);
    const isText = typeof input.description === 'string' && input.description.trim().length > 1;
    if (!isText && (!input.image || !/^data:image\/(jpeg|png|webp);base64,/.test(input.image))) return output({error:'Enter a food or select a photo.'});
    if (!isText && input.image.length > 2800000) return output({error:'Photo is too large.'});
    if (isText && input.description.length > 500) return output({error:'Food description is too long.'});
    const key = PropertiesService.getScriptProperties().getProperty('GEMINI_API_KEY');
    if (!key) return output({error:'Gemini API key is not configured.'});
    const parts = isText ? [] : input.image.split(',');
    const mimeType = isText ? '' : parts[0].match(/^data:(image\/[^;]+);/)[1];
    const payload = {
      contents:[{parts:[
        {text:(isText ? 'Estimate the nutrition of this described food and quantity: '+input.description.trim() : 'Analyze this meal photo, including any visible nutrition facts label.')+' Respond with JSON only: {"name":"short food description","serving":"estimated portion size","calories":number,"protein":number,"carbs":number,"fat":number,"notes":"brief uncertainty warning"}. For a clear nutrition label use printed values and serving size; otherwise estimate realistic meal portions. Values are estimates. Account for likely oils/sauces, but do not pretend exactness.'},
        ...(isText ? [] : [{inline_data:{mime_type:mimeType,data:parts[1]}}])
      ]}],
      generationConfig:{responseMimeType:'application/json',temperature:0.2}
    };
    const url='https://generativelanguage.googleapis.com/v1beta/models/'+MODEL+':generateContent';
    const response=UrlFetchApp.fetch(url,{
      method:'post',
      contentType:'application/json',
      headers:{'x-goog-api-key':key},
      payload:JSON.stringify(payload),
      muteHttpExceptions:true
    });
    if(response.getResponseCode()!==200) {
      console.error('Gemini error status '+response.getResponseCode());
      return output({error:'Gemini analysis unavailable. Check API access and try again.'});
    }
    const raw=JSON.parse(response.getContentText());
    const txt=raw.candidates?.[0]?.content?.parts?.map(p=>p.text||'').join('')||'';
    const data=JSON.parse(txt);
    if(data.error)return output({error:String(data.error).slice(0,160)});
    for(const field of ['calories','protein','carbs','fat']){
      const n=Number(data[field]);
      if(!Number.isFinite(n)||n<0||n>10000)throw Error('Invalid estimate');
      data[field]=Math.round(n*10)/10;
    }
    return output({
      name:String(data.name||'Meal').slice(0,120),
      serving:String(data.serving||'').slice(0,100),
      calories:data.calories,protein:data.protein,carbs:data.carbs,fat:data.fat,
      notes:String(data.notes||'Estimate only; review portions and ingredients.').slice(0,240)
    });
  } catch(err) {
    console.error(err);
    return output({error:'Could not analyze photo. Please try again.'});
  }
}
function output(value){
  return ContentService.createTextOutput(JSON.stringify(value))
    .setMimeType(ContentService.MimeType.JSON);
}
